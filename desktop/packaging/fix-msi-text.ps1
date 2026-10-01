# Corrects a typo in jpackage's own installer text, in a built MSI: "The folder ... already exist."
# (from the JDK's MsiInstallerStrings_en.wxl). jpackage would accept a corrected
# InstallDirNotEmptyDlg.wxs via --resource-dir, but the Compose Gradle plugin always passes its own
# (empty, cleared on every build) --resource-dir last, which wins — so the fix is applied to the
# finished MSI's Control table instead. Run by build.gradle.kts after packageMsi/packageReleaseMsi.
param([Parameter(Mandatory = $true)][string]$Msi)
$ErrorActionPreference = "Stop"
$text = "The folder [INSTALLDIR] already exists. Would you like to install to that folder anyway?"
$installer = New-Object -ComObject WindowsInstaller.Installer
$db = $installer.GetType().InvokeMember("OpenDatabase", "InvokeMethod", $null, $installer, @($Msi, 1)) # 1 = transact
$sql = "UPDATE ``Control`` SET ``Text`` = '$text' WHERE ``Dialog_`` = 'InstallDirNotEmptyDlg' AND ``Control`` = 'Text'"
$view = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @($sql))
$view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null) | Out-Null
$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null) | Out-Null
$db.GetType().InvokeMember("Commit", "InvokeMethod", $null, $db, $null) | Out-Null
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($db) | Out-Null
Write-Output "Fixed installer text in $Msi"
