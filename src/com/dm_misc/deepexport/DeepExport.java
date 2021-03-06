package com.dm_misc.deepexport;

/* ============================================================================
 * DeepExport
 * (c) 2015 - 2019 MSRoth
 * 
 * This application will export content from a Documentum repository and 
 * replicate the repository file structure on your hard drive.  Objects to 
 * export are selected according to the properties contained in the 
 * deepexport.properties file.
 * 
 * The content will be exported in its native format and named according to its 
 * object_name with an extension based upon its a_content_type. Duplicate file
 * names will be appended with a counter (i.e., they will not be overwritten).
 * 
 *  * NO METADATA IS EXPORTED *
 * 
 * If you need to export metadata with the content, please see my
 * QuikDIE application:
 * 
 * https://msroth.wordpress.com/2015/03/26/documentum-bulk-export-tool-v1-4/
 * 
 * DeepExport can be invoke using two command line switches:
 *   -versions : export all versions of documents
 *   -help    : display a brief help screen
 * 
 * Sample deepexport.properties file:
 *   docbase.name=repo1
 *   docbase.user=dmadmin
 *   docbase.password=dmadmin
 *   export.source=/Temp
 *   export.target=c:/temp/export
 * 
 * A clear text password will be encrypted and rewritten to the properties file
 * after the first run of the DeepExport application.
 * 
 * Tested on Documentum Content Server 6.7 SP2, 7.0, 7.1.   
 *   
 * Versions:
 *   1.0 - 2015-01-05 - initial release.
 *   1.1 - 2015-07-06 - added code to omit objects parked on BOCS servers
 *                    - updated code to use DCTMBasics v1.3
 *                    - refactored code to remove Utils class
 *                    - added password encryption/decryption
 *   1.2 - 2019-09-12 - updated copyright
 *                    - corrected initial document count if using versions
 *                    - a little refactoring
 *   1.3 - 2019-11-21 - implemented dmRecordSet to avoid hitting IDfCollection
 *                      max
 *   
 */   

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import com.dm_misc.dctm.DCTMBasics;
import com.dm_misc.collections.dmRecordSet;

import com.documentum.fc.client.IDfCollection;
import com.documentum.fc.client.IDfDocument;
import com.documentum.fc.client.IDfFolder;
import com.documentum.fc.client.IDfSession;
import com.documentum.fc.client.IDfSysObject;
import com.documentum.fc.client.IDfTypedObject;
import com.documentum.fc.common.DfException;
import com.documentum.fc.common.IDfId;
import com.documentum.fc.tools.RegistryPasswordUtils;


public class DeepExport {


	private final String DOCBASE_KEY = "docbase.name";
	private final String USERNAME_KEY = "docbase.user";
	private final String PASSWORD_KEY = "docbase.password";
	private final String SOURCE_PATH_KEY = "export.source";
	private final String EXPORT_PATH_KEY = "export.target";
	private final String USE_VERSIONS_KEY = "-versions";
	private final String HELP_KEY = "-help";
	private final String PROPERTIES_FILE = "deepexport.properties";
	private final String PASSWORD_PREFIX = "DM_ENCR_TEXT=";

	private Properties m_exportProps;
	private static IDfSession m_session;
	private boolean m_useVersions = false;
	private boolean m_useHelp = false;
	private int m_foldersExported = 0;
	private int m_filesExported = 0;
	private PrintWriter m_log;
	private String m_logstub = "DCTMDeepExport_%s.log";

	private static String VERSION = "1.3";

	public static void main(String[] args) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd-HH:mm:ss");
		Long startTime = 0L;
		Long endTime = 0L;

		try {

			// get current time and print harness header
			System.out.println("===== Start Documentum Deep Export v" + VERSION + " " + sdf.format(new Date()) + " =====");
			System.out.println("(C) 2015-2019 MSRoth - msroth.wordpress.com");
			System.out.println();
			startTime = System.currentTimeMillis();

			// =================
			// instantiate class here
			DeepExport d = new DeepExport();
			d.run(args);
			// =================

		} catch (Exception e) {
			System.out.println("ERROR: " + e.getMessage());
			//e.printStackTrace();
		}  finally {
			if (m_session != null)
				m_session.getSessionManager().release(m_session);

			// get current time and print harness footer
			System.out.println();
			endTime = System.currentTimeMillis();
			System.out.println("Total Run Time: " + ((endTime - startTime) / 1000) + " sec");
			System.out.println("===== End Documentum Deep Export " + sdf.format(new Date()) + " =====");
		}
	}

	public DeepExport() {
	}

	private void run(String[] args) throws Exception {
		int folders_found = 0;
		int documents_found = 0;
		String query = "";
		File basePath;

		// read properties file
		m_exportProps = readPropertiesFile(PROPERTIES_FILE);

		// encrypt/decrypt password if it needs it
		checkPasswordEncryption();

		// add default properties
		m_exportProps.put(HELP_KEY, "false");
		m_exportProps.put(USE_VERSIONS_KEY, "false");

		// get args
		m_exportProps.putAll(getArgs(args));

		// get boolean switches
		m_useHelp = Boolean.parseBoolean(m_exportProps.getProperty(HELP_KEY));
		m_useVersions = Boolean.parseBoolean(m_exportProps.getProperty(USE_VERSIONS_KEY));

		if (m_useHelp) {
			printUsage();
		} else {

			// validate properties
			if (validateProperties()) {

				// log on
				System.out.print("Logging onto Documentum...");
				m_session = DCTMBasics.logon(m_exportProps.getProperty(DOCBASE_KEY), 
						m_exportProps.getProperty(USERNAME_KEY), 
						m_exportProps.getProperty(PASSWORD_KEY));

				if (m_session != null) {
					System.out.println("Success (" + m_exportProps.getProperty(USERNAME_KEY)+ "@" + m_exportProps.getProperty(DOCBASE_KEY)+ ")");
					System.out.println();

					// ensure EXPORT_PATH_KEY exists
					basePath = new File(trimTrailingSlash(m_exportProps.getProperty(EXPORT_PATH_KEY)));
					if (! basePath.exists())
						throw new DfException("Export path " + m_exportProps.getProperty(EXPORT_PATH_KEY) + " does not exist.");
					if (! basePath.isDirectory())
						throw new DfException("Export path " + m_exportProps.getProperty(EXPORT_PATH_KEY) + " is not a folder.");

					// open log file
					m_log = openLogFile(m_exportProps.getProperty(EXPORT_PATH_KEY), m_logstub);

					// print base export path
					System.out.println("Export target path = " + trimTrailingSlash(m_exportProps.getProperty(EXPORT_PATH_KEY)));
					m_log.println("Export target path = " + trimTrailingSlash(m_exportProps.getProperty(EXPORT_PATH_KEY)));

					// ensure SOURCE_PATH_KEY exists
					query = "select count(*) as _cnt from dm_folder where any r_folder_path = '" + trimTrailingSlash(m_exportProps.getProperty(SOURCE_PATH_KEY)) + "'";
					int fnd = Integer.parseInt(DCTMBasics.runDQLQueryReturnSingleValue(query, m_session));
					if (fnd < 1)
						throw new DfException("Source path " + m_exportProps.getProperty(SOURCE_PATH_KEY) + " does not exist in repository.");

					// print export source path
					System.out.println("Export source path = " + trimTrailingSlash(m_exportProps.getProperty(SOURCE_PATH_KEY)));
					m_log.println("Export source path = " + trimTrailingSlash(m_exportProps.getProperty(SOURCE_PATH_KEY)));

					// count export folders
					query = "select count(*) as _cnt from dm_folder where folder('" + m_exportProps.getProperty(SOURCE_PATH_KEY) + "',descend)";
					folders_found = Integer.parseInt(DCTMBasics.runDQLQueryReturnSingleValue(query, m_session));

					// count export documents with versions
					if (m_useVersions) {
						query = "select count(*) as _cnt from dm_document (ALL) where folder('" + m_exportProps.getProperty(SOURCE_PATH_KEY) + "',descend) and r_full_content_size > 0";
						documents_found = Integer.parseInt(DCTMBasics.runDQLQueryReturnSingleValue(query, m_session));
					} else {
						// count export documents without
						query = "select count(*) as _cnt from dm_document where folder('" + m_exportProps.getProperty(SOURCE_PATH_KEY) + "',descend) and r_full_content_size > 0";
						documents_found = Integer.parseInt(DCTMBasics.runDQLQueryReturnSingleValue(query, m_session));
					}
					
					// print expected totals
					System.out.println("Found " + folders_found + " sub folders to export");
					System.out.println("Found " + documents_found + " documents to export\n");
					m_log.println("Found " + folders_found + " sub folders to export");
					m_log.println("Found " + documents_found + " documents to export\n");

					// *** call export method *** 
					System.out.println("Exporting Folder: " + m_exportProps.getProperty(SOURCE_PATH_KEY));
					m_log.println("Exporting Folder: " + m_exportProps.getProperty(SOURCE_PATH_KEY));
					IDfFolder folder = (IDfFolder) m_session.getObjectByQualification("dm_folder where any r_folder_path = '" + m_exportProps.getProperty(SOURCE_PATH_KEY) + "'");
					exportFolder(folder);

					// print actual totals
					System.out.println();
					System.out.println("\nFolders processed: " + m_foldersExported);
					System.out.println("Documents processed: " + m_filesExported);
					m_log.println("\nFolders processed: " + m_foldersExported);
					m_log.println("Documents processed: " + m_filesExported);

				} else {
					System.out.println("Failed. (" + m_exportProps.getProperty(USERNAME_KEY)+ "@" + m_exportProps.getProperty(DOCBASE_KEY)+ ")");
				}
			} else {
				System.out.println("Invalid properties or arguments.");
				printUsage();
			}
		}
		// close the log file
		if (m_log != null) {
			m_log.println("End Documentum Deep Export");
			m_log.close();
		}
	}

	
	private void exportFolder(IDfFolder folder) throws Exception {
		
		// create this folder on the target file system
		String exportPath = createFolderOnFileSystem(m_exportProps.getProperty(EXPORT_PATH_KEY), folder.getFolderPath(0));

		// build query to get sysobjects in this folder
		String query = "select r_object_id from dm_sysobject ";
		if (m_useVersions)
			query += "(ALL) ";
		query += " where folder(id('" + folder.getObjectId().toString() + "'))";

		// run query
		IDfCollection col = DCTMBasics.runSelectQuery(query, m_session);
		// --v1.3
		dmRecordSet dmRS = new dmRecordSet(col);
		col.close();
		
		// process query
		while (dmRS.hasNext()) {
			// -- v1.3
			IDfTypedObject rsTobj = dmRS.getNextRow();
			IDfSysObject sObj = (IDfSysObject) m_session.getObject(rsTobj.getId("r_object_id"));

			// if it is a folder set up for recursive call
			if (DCTMBasics.isFolder(sObj)) {
				IDfFolder f = (IDfFolder) sObj;
				System.out.println("Exporting Folder: " + f.getFolderPath(0));
				m_log.println("Exporting Folder: " + f.getFolderPath(0));
				m_foldersExported++;

				// recursive call with new found folder
				exportFolder(f);

			} else {

				// if it isn't a folder, make sure the object is a document
				if (DCTMBasics.isDocument(sObj)) {

					// make sure it has content
					if (DCTMBasics.hasContent((IDfDocument) sObj)) {

						// check if doc parked on BOCS
						IDfId contId = sObj.getContentsId();
						if (contId.isObjectId()) {
							IDfTypedObject tObj = (IDfTypedObject) m_session.getObject(contId);
							if (tObj != null) {
								int parked = tObj.getInt("i_parked_state");  
								if (parked == 0) {

									// *** if it is a doc with content and not parked, export it ***
									exportContentToFileSystem(exportPath, (IDfDocument) sObj);

								} else {
									m_log.println("\tObject is parked -- skipping " + sObj.getObjectName() + "\t(" + sObj.getObjectId().toString() + ")");
								}
							} else {
								m_log.println("\tCannot get associated dmr_content object -- skipping " + sObj.getObjectName() + "\t(" + sObj.getObjectId().toString() + ")");
							}
						} else {
							m_log.println("\tObject has no associate dmr_content object -- skipping " + sObj.getObjectName() + "\t(" + sObj.getObjectId().toString() + ")");
						}
					} else {
						m_log.println("\tNo content -- skipping " + sObj.getObjectName() + "\t(" + sObj.getObjectId().toString() + ")");
					}
				} else {
					m_log.println("\tNot a document -- skipping " + sObj.getObjectName() + "\t(" + sObj.getObjectId().toString() + ")");
				}
			}
		}
	}


	private void exportContentToFileSystem(String exportPath, IDfDocument doc) {
		String filename = "";
		String version = "";
		String extension = "";
		String fullFileName = "";

		try {
			m_filesExported++;
			filename = sanitizeFileName(doc.getObjectName());

			// account for version
			if (m_useVersions) {
				version = doc.getVersionLabels().getImplicitVersionLabel();
				version = "-v" + version;
			}

			// add file type extension
			extension = "." + doc.getFormat().getDOSExtension();

			// make filenames unique
			boolean unique = false;
			int counter = 0;
			while (!unique) {
				if (counter == 0)
					fullFileName = exportPath + "/" + filename + version + extension;
				else
					fullFileName = exportPath + "/" + filename + version + "_(" + counter + ")" + extension;

				File f = new File(fullFileName);
				if (!f.exists()) {
					unique = true;
					break;
				} else {
					unique = false;
					counter++;
				}	
			}

			// *** export the document to the constructed file name and path ***
			doc.getFile(fullFileName);
			System.out.println("\tExporting Document: " + doc.getObjectName() +  " [" + doc.getObjectId().toString() + "]");
			m_log.println("\tExporting Document: " + doc.getObjectName() +  " [" + doc.getObjectId().toString() + "]\t--> " + fullFileName);

		} catch (DfException dfe) {
			System.out.println("ERROR: Could not export " + fullFileName + " - " + dfe.getMessage());
		}
	}


	private Properties getArgs(String[] args) {
		Properties props = new Properties();

		for (int i=0; i<args.length; i++) {
			String key = args[i];

			if (key.equalsIgnoreCase(USE_VERSIONS_KEY))
				props.setProperty(USE_VERSIONS_KEY, "true");
			else if (key.equalsIgnoreCase(HELP_KEY))
				props.setProperty(HELP_KEY, "true");
			else {
				System.out.println("WARNING: Unknown argument: " + key);
				props.setProperty(HELP_KEY, "true");
			}
		}
		return props;
	}

	private boolean validateProperties() {
		boolean valid = true;

		if (!m_exportProps.containsKey(DOCBASE_KEY) ||
				!m_exportProps.containsKey(USERNAME_KEY) ||
				!m_exportProps.containsKey(PASSWORD_KEY) ||
				!m_exportProps.containsKey(SOURCE_PATH_KEY) ||
				!m_exportProps.containsKey(EXPORT_PATH_KEY) ||
				!m_exportProps.containsKey(HELP_KEY) ||
				!m_exportProps.containsKey(USE_VERSIONS_KEY))
			valid = false;
		if (valid) {
			if (m_exportProps.getProperty(DOCBASE_KEY).trim().isEmpty() || 
					m_exportProps.getProperty(USERNAME_KEY).trim().isEmpty() ||	
					m_exportProps.getProperty(PASSWORD_KEY).trim().isEmpty() ||
					m_exportProps.getProperty(SOURCE_PATH_KEY).trim().isEmpty() ||
					m_exportProps.getProperty(EXPORT_PATH_KEY).trim().isEmpty() || 
					m_exportProps.getProperty(HELP_KEY).trim().isEmpty() ||
					m_exportProps.getProperty(USE_VERSIONS_KEY).trim().isEmpty())
				valid = false;
		}
		return valid;	
	}


	private PrintWriter openLogFile(String logpath, String logfile) throws DfException {
		PrintWriter log = null;

		// get date format
		SimpleDateFormat sdf1 = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
		SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String date = sdf1.format(new Date());

		// patch logfile name
		logfile = String.format(logfile,date);

		// make log dir if necessary
		logpath = createFolderOnFileSystem(logpath, null);

		// open log file
		try {
			logfile = logpath + "/" + logfile;
			log = new PrintWriter(new FileWriter(logfile));
			System.out.println("Log file = " + logfile);

			log.println("Documentum Deep Export " + sdf2.format(new Date()));
			log.println("(C) 2014 MSRoth - msroth.wordpress.com");
			log.println();

		} catch (Exception e) {
			throw new DfException (" openLogFile - " + e.getMessage());
		}
		return log;
	}


	private void printUsage() {
		System.out.println();
		System.out.println("Usage:");
		System.out.println("The DeepExport utility expects to read the following values");
		System.out.println("from the deepexport.properties file:");
		System.out.println();
		System.out.println("docbase.name");
		System.out.println("docbase.user");
		System.out.println("docbase.password");
		System.out.println("export.source");
		System.out.println("export.target");
		System.out.println();
		System.out.println("In addition, two command line parameters are accepted:");
		System.out.println("-versions will export all versions of all documents found");
		System.out.println("-help will display this screen");
		System.out.println();
	}


	private Properties readPropertiesFile(String propFile) {
		Properties props = new Properties();

		try {
			// try loading from current dir
			File f = new File(propFile);
			InputStream is = new FileInputStream(f);

			if (is != null) 
				props.load(is);
		} catch (Exception e) {
			System.out.println("Cannot read properties file " + e.getMessage() + ".");
		}
		return props;
	}


	private String trimTrailingSlash(String path) {
		if (path.endsWith("/") || path.endsWith("\\"))
			path = path.substring(path.length()-1);
		return path;
	}


	private String sanitizeFileName(String filename) {
		filename = filename.replace(":","_");
		filename = filename.replace("/","-");
		filename = filename.replace("\\","-");
		return filename;
	}


	private String createFolderOnFileSystem(String basePath, String folderPath) {
		File dir = null;

		basePath = trimTrailingSlash(basePath);
		if ((folderPath != null) && (!folderPath.trim().isEmpty())) 
			dir = new File(basePath + "/" + folderPath);
		else
			dir = new File(basePath);

		dir.mkdirs();
		return dir.getAbsolutePath();
	}

	private void checkPasswordEncryption() {

		try {

			// if password not encrypted in property file, encrypt it and save
			// it back to the property file.  Leave it un-encrypted in properties
			// hash
			String password = m_exportProps.getProperty(PASSWORD_KEY);
			if (!password.startsWith(PASSWORD_PREFIX)) {

				//write to property file
				password = writePasswordToPropertyFile();
			}

			// chop off DM_ENCR_TEXT=
			password = password.substring(PASSWORD_PREFIX.length());
			password = password.replace("\\", "");
			String newPassword = RegistryPasswordUtils.decrypt(password);
			m_exportProps.setProperty(PASSWORD_KEY, newPassword);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private String writePasswordToPropertyFile() {
		String newPassword = "";

		try {

			// encrypt password
			newPassword = PASSWORD_PREFIX + RegistryPasswordUtils.encrypt(m_exportProps.getProperty(PASSWORD_KEY));
			newPassword = newPassword.replace("\\", "");

			// this assumes the property file is in the current dir
			File file = new File(PROPERTIES_FILE);
			m_exportProps.setProperty(PASSWORD_KEY, newPassword);
			OutputStream out = new FileOutputStream(file);
			m_exportProps.store(out, "");

		} catch (Exception e) {
			System.out.println("\tWARNING:  unable to write encrypted password to property file " + e.getMessage());
		}

		return newPassword;
	}
}

// <SDG><

