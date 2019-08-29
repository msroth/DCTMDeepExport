# DCTMDeepExport
This application will export content (only) from a Documentum repository and replicate the repository file structure on your hard drive. 

dm_document objects to export are designated by indicating a folder in the deepexport.properties file.
 
The content will be exported in its native format and named according to its object_name with an extension based upon its a_content_type. 
Duplicate file names will be appended with a counter (i.e., they will not be overwritten).

 * NO METADATA IS EXPORTED
 
If you need to export metadata with the content, please see my QuikDIE application:
https://msroth.wordpress.com/2015/03/26/documentum-bulk-export-tool-v1-4/
 
 DeepExport can be invoke using two command line switches:
   -versions : export all versions of documents
   -help    : display a brief help screen
 
 Sample deepexport.properties file:
   <pre>
   docbase.name=repo1
   docbase.user=dmadmin
   docbase.password=dmadmin
   export.source=/Temp
   export.target=c:/temp/export
   </pre>
 
 A clear text password will be encrypted and rewritten to the properties file after the first run of the DeepExport application.
 
 Tested on Documentum Content Server 6.7 SP2, 7.0, 7.1.   
