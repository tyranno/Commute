$ErrorActionPreference='Stop'
[void][Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementPublisher,Windows.System.Runtime,ContentType=WindowsRuntime]
[void][Windows.Storage.Streams.DataWriter,Windows.System.Runtime,ContentType=WindowsRuntime]

$pub = New-Object Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementPublisher

# Manufacturer data: company id 0xFFFF, payload "COMMUTE1"
$md = New-Object Windows.Devices.Bluetooth.Advertisement.BluetoothLEManufacturerData
$md.CompanyId = 0xFFFF
$w = New-Object Windows.Storage.Streams.DataWriter
$w.WriteString("COMMUTE1")
$md.Data = $w.DetachBuffer()
$pub.Advertisement.ManufacturerData.Add($md)

$pub.Start()
Write-Output ("Status: " + $pub.Status)
# keep the process (and the advertisement) alive
while ($true) { Start-Sleep -Seconds 5 }
