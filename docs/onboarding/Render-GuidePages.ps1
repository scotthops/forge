param([Parameter(Mandatory=$true)][string]$PdfPath,[Parameter(Mandatory=$true)][string]$OutputDirectory)
# Windows PDF rasterization for document visual QA, using the operating system renderer.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime] | Out-Null
[Windows.Data.Pdf.PdfDocument,Windows.Data.Pdf,ContentType=WindowsRuntime] | Out-Null
[Windows.Data.Pdf.PdfPageRenderOptions,Windows.Data.Pdf,ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.Streams.InMemoryRandomAccessStream,Windows.Storage.Streams,ContentType=WindowsRuntime] | Out-Null
$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
function Await-Operation($operation,[Type]$type) {
    $task=$asTaskGeneric.MakeGenericMethod($type).Invoke($null,@($operation));$task.Wait();return $task.Result
}
function Await-Action($operation) {
    $method=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and -not $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' } | Select-Object -First 1
    $task=$method.Invoke($null,@($operation));$task.Wait()
}
$file=Await-Operation ([Windows.Storage.StorageFile]::GetFileFromPathAsync([IO.Path]::GetFullPath($PdfPath))) ([Windows.Storage.StorageFile])
$pdf=Await-Operation ([Windows.Data.Pdf.PdfDocument]::LoadFromFileAsync($file)) ([Windows.Data.Pdf.PdfDocument])
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutputDirectory)) | Out-Null
for($i=0;$i -lt $pdf.PageCount;$i++) {
    $page=$pdf.GetPage($i)
    $stream=[Windows.Storage.Streams.InMemoryRandomAccessStream]::new()
    $options=[Windows.Data.Pdf.PdfPageRenderOptions]::new();$options.DestinationWidth=1530;$options.DestinationHeight=1980
    Await-Action ($page.RenderToStreamAsync($stream,$options))
    $stream.Seek(0)
    $input=[System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
    $destination=[IO.File]::Create((Join-Path $OutputDirectory ('page-'+($i+1)+'.png')))
    $input.CopyTo($destination);$destination.Dispose();$input.Dispose();$stream.Dispose();$page.Dispose()
}
Write-Output "Rendered $($pdf.PageCount) pages to $OutputDirectory"
