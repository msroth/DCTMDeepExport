@echo off
cls
REM ==============================
REM CHANGE THIS PATH AS NECESSARY
set CONFIG_DIR=c:\documentum\config
REM ===============================
set DFC_PROPERTIES=%CONFIG_DIR%/dfc.properties
echo.
echo.
REM make sure deepexport.properties file exists
if exist deepexport.properties goto check_config_dir
echo Cannot find deepexport.properties file in this directory.

REM check that the dfc.properties file can be found
:check_config_dir
if exist %DFC_PROPERTIES% goto run_export
echo Cannot find dfc.properties file in %CONFIG_DIR%.  Please change the value of the CONFIG_DIR variable in this batch file and try Export again.
goto end

REM run Export
:run_export
java -classpath "%CLASSPATH%;%CONFIG_DIR;dist/DCTMDeepExport.jar;lib/DCTMBasics.jar" com.dm_misc.deepexport.DeepExport

:end
echo.
echo.
pause
