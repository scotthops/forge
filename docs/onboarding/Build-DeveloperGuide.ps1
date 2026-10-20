param([string]$OutputDirectory = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
$out = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($out) | Out-Null
$source = Join-Path $PSScriptRoot 'MTG_Premodern_Client_Developer_Guide.md'
$docxPath = Join-Path $out 'MTG_Premodern_Client_Developer_Guide.docx'

# Deterministic diagrams, rendered at a high resolution for readable print labels.
function New-Canvas([int]$height) {
    $script:canvas = [System.Drawing.Bitmap]::new(1800, $height)
    $script:g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::White)
    $script:font = [System.Drawing.Font]::new('Calibri', 30, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $script:boldFont = [System.Drawing.Font]::new('Calibri', 34, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $script:pen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(55,55,55), 3)
    $script:arrowPen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(55,55,55), 3)
    $arrowPen.CustomEndCap = [System.Drawing.Drawing2D.AdjustableArrowCap]::new(7,8)
}
function Label([string]$text, [float]$x, [float]$y, [float]$width, [float]$height, [bool]$bold=$false) {
    $format = [System.Drawing.StringFormat]::new()
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $chosenFont = if($bold){$boldFont}else{$font}
    $g.DrawString($text, $chosenFont, [System.Drawing.Brushes]::Black, [System.Drawing.RectangleF]::new($x,$y,$width,$height),$format)
    $format.Dispose()
}
function Box([string]$title, [string]$body, [float]$x,[float]$y,[float]$width,[float]$height) {
    $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(242,245,248))
    $g.FillRectangle($brush,$x,$y,$width,$height)
    $g.DrawRectangle($pen,$x,$y,$width,$height)
    Label $title ($x+10) ($y+12) ($width-20) 55 $true
    Label $body ($x+20) ($y+72) ($width-40) ($height-82)
    $brush.Dispose()
}
function Arrow([float]$x1,[float]$y1,[float]$x2,[float]$y2) {$g.DrawLine($arrowPen,$x1,$y1,$x2,$y2)}
function Save-Canvas([string]$name) {
    $canvas.Save((Join-Path $out $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $canvas.Dispose(); $font.Dispose(); $boldFont.Dispose(); $pen.Dispose(); $arrowPen.Dispose()
}
New-Canvas 810
Box 'Godot process' "Main and CardControl`nC# UI plus pending decisions" 40 80 470 240
Box 'Bridge JVM' "BridgeGuiGame and BridgeProtocol`nReceived views and JSON projection" 650 80 510 240
Box 'Forge host JVM' "FServerManager and lobby`nHuman controller plus rules engine" 1300 80 460 240
Arrow 510 140 650 140; Arrow 650 265 510 265
Label "JSONL`nstdin / stdout" 515 150 130 100
Arrow 1160 140 1300 140; Arrow 1300 265 1160 265
Label "TCP`nobjects" 1165 150 130 100
Box 'Presentation only' "Preview, duplicate badges, draft controls`nNo optimistic game-state changes" 40 430 520 240
Box 'Adapter and replica' "Offered IDs, freshness, query correlation`nVisibility-safe full JSON state" 650 430 510 240
Box 'Authoritative Forge' "Game, Cards, Players, active Input`nRules, legality, RNG, costs, combat" 1250 430 510 240
Arrow 275 320 275 420; Arrow 905 320 905 420; Arrow 1510 320 1510 420
Label 'Two humans use two Godot and bridge pairs connected to the same host' 90 720 1620 55 $true
Save-Canvas 'architecture.png'

New-Canvas 1120
$cols = @(210,650,1110,1580)
$heads = @('Godot scene','Bridge GUI','Bridge input','Forge host')
for($i=0;$i -lt 4;$i++) {
    Box $heads[$i] '' ($cols[$i]-175) 25 350 95
    $g.DrawLine($pen,$cols[$i],120,$cols[$i],1030)
}
function Seq([int]$a,[int]$b,[int]$y,[string]$text) {
    Arrow $cols[$a] $y $cols[$b] $y
    $left=[Math]::Min($cols[$a],$cols[$b]);$width=[Math]::Abs($cols[$a]-$cols[$b])
    $brush=[System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
    $g.FillRectangle($brush,($left+8),($y-52),($width-16),48)
    Label $text ($left+8) ($y-57) ($width-16) 55
    $brush.Dispose()
}
Seq 0 2 205 'selectCard and interactionSequence'
Seq 2 1 315 'validate and dispatch command'
Seq 1 3 425 'NetGameController via TCP'
Seq 3 1 540 'GUI ability callback via TCP'
Seq 1 0 655 'query with requestId'
$g.FillRectangle([System.Drawing.Brushes]::White,485,695,330,85)
Label "GUI callback waits`non a future" 485 695 330 85 $true
Seq 0 2 820 'reply with requestId and selectedId'
Seq 2 1 900 'complete future directly'
Seq 1 3 970 'return choice via network ReplyEvent'
Label 'Forge advances then sends view or delta to the bridge for a new JSON state' 220 1040 1400 60 $true
Save-Canvas 'sequence.png'

function Escape([string]$s) { return [System.Security.SecurityElement]::Escape($s) }
function Runs([string]$s) {
    $result=[System.Text.StringBuilder]::new()
    foreach($m in [regex]::Matches($s,'(`[^`]+`|\*\*[^*]+\*\*|[^`*]+|.)')) {
        $v=$m.Value; $props=''
        if($v.StartsWith('`') -and $v.EndsWith('`')) { $v=$v.Substring(1,$v.Length-2);$props='<w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="19"/></w:rPr>' }
        elseif($v.StartsWith('**') -and $v.EndsWith('**')) {$v=$v.Substring(2,$v.Length-4);$props='<w:rPr><w:b/></w:rPr>'}
        [void]$result.Append('<w:r>'+$props+'<w:t xml:space="preserve">'+(Escape $v)+'</w:t></w:r>')
    }
    return $result.ToString()
}
function Paragraph([string]$s,[string]$style='Normal',[string]$extra='') {
    return '<w:p><w:pPr><w:pStyle w:val="'+$style+'"/>'+$extra+'</w:pPr>'+(Runs $s)+'</w:p>'
}
$body=[System.Text.StringBuilder]::new()
$rels=[System.Collections.Generic.List[string]]::new()
$rels.Add('<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>')
$rels.Add('<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>')
$imageFiles=[System.Collections.Generic.List[string]]::new()
$lines=[System.IO.File]::ReadAllLines($source)
$buffer=[System.Collections.Generic.List[string]]::new()
$table=[System.Collections.Generic.List[string]]::new()
$inCode=$false;$chapter=0;$imageNumber=0
function Flush-Paragraph {
    if($buffer.Count -gt 0) {[void]$body.Append((Paragraph ($buffer -join ' ')));$buffer.Clear()}
}
function Flush-Table {
    if($table.Count -eq 0){return}
    $rows=@($table | Where-Object {$_ -notmatch '^\|[\s:|\-]+\|$'} | ForEach-Object { ,@($_.Trim('|').Split('|') | ForEach-Object {$_.Trim()}) })
    $count=$rows[0].Count
    $widths=if($count -eq 2){@(2550,7674)}else{@(2300,3962,3962)}
    [void]$body.Append('<w:tbl><w:tblPr><w:tblW w:w="10224" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>')
    foreach($edge in @('top','left','bottom','right','insideH','insideV')){[void]$body.Append('<w:'+$edge+' w:val="single" w:sz="4" w:color="D9D9D9"/>')}
    [void]$body.Append('</w:tblBorders><w:tblCellMar><w:top w:w="95" w:type="dxa"/><w:left w:w="100" w:type="dxa"/><w:bottom w:w="95" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid>')
    foreach($w in $widths){[void]$body.Append('<w:gridCol w:w="'+$w+'"/>')};[void]$body.Append('</w:tblGrid>')
    for($r=0;$r -lt $rows.Count;$r++) {
        $rowProps='<w:cantSplit/>';if($r -eq 0){$rowProps+='<w:tblHeader/>'}
        [void]$body.Append('<w:tr><w:trPr>'+$rowProps+'</w:trPr>')
        for($c=0;$c -lt $count;$c++) {
            $shade=if($r -eq 0){'E5EBF0'}elseif($r % 2 -eq 0){'F6F8FA'}else{'FFFFFF'}
            $sty=if($r -eq 0){'TableHeader'}else{'TableText'}
            [void]$body.Append('<w:tc><w:tcPr><w:tcW w:w="'+$widths[$c]+'" w:type="dxa"/><w:vAlign w:val="center"/><w:shd w:fill="'+$shade+'"/></w:tcPr>'+(Paragraph $rows[$r][$c] $sty)+'</w:tc>')
        }
        [void]$body.Append('</w:tr>')
    }
    [void]$body.Append('</w:tbl>');[void]$body.Append((Paragraph '' 'TableAfter'));$table.Clear()
}
for($i=0;$i -lt $lines.Length;$i++) {
    $line=$lines[$i]
    if($line.StartsWith('```')){Flush-Paragraph;Flush-Table;$inCode=-not $inCode;continue}
    if($inCode){
        $keepNext=if($i+1 -lt $lines.Length -and -not $lines[$i+1].StartsWith('```')){'<w:keepNext/>'}else{''}
        [void]$body.Append('<w:p><w:pPr><w:pStyle w:val="Code"/>'+$keepNext+'</w:pPr><w:r><w:t xml:space="preserve">'+(Escape $line)+'</w:t></w:r></w:p>');continue
    }
    if($line.StartsWith('|')){Flush-Paragraph;$table.Add($line);continue}else{Flush-Table}
    if($line -eq '<!-- PAGEBREAK -->'){Flush-Paragraph;[void]$body.Append('<w:p><w:r><w:br w:type="page"/></w:r></w:p>');continue}
    if($line -match '^!\[([^]]*)\]\(([^)]+)\)$') {
        Flush-Paragraph;$imageNumber++;$file=$Matches[2];$alt=$Matches[1];$imageFiles.Add($file)
        $rels.Add('<Relationship Id="rIdImage'+$imageNumber+'" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/'+$file+'"/>')
        $bitmap=[System.Drawing.Image]::FromFile((Join-Path $out $file));$cx=6492240;$cy=[long]($cx*$bitmap.Height/$bitmap.Width);$bitmap.Dispose()
        [void]$body.Append('<w:p><w:pPr><w:jc w:val="center"/><w:keepNext/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="'+$cx+'" cy="'+$cy+'"/><wp:docPr id="'+$imageNumber+'" name="'+$file+'" descr="'+(Escape $alt)+'"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="0" name="'+$file+'"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rIdImage'+$imageNumber+'"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="'+$cx+'" cy="'+$cy+'"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>');continue
    }
    if($line -match '^# (.+)$') {
        Flush-Paragraph;$title=$Matches[1]
        if($i -eq 0){[void]$body.Append((Paragraph $title 'Title'))}
        else{
            $chapter++
            $pageBreak=if($title -match '^(1 |9 |Appendix )'){'<w:pageBreakBefore/>'}else{''}
            [void]$body.Append((Paragraph $title 'Heading1' $pageBreak))
        }
        continue
    }
    if($line -match '^## (.+)$'){Flush-Paragraph;[void]$body.Append((Paragraph $Matches[1] 'Heading2'));continue}
    if($line -match '^- (.+)$'){Flush-Paragraph;[void]$body.Append((Paragraph ('• '+$Matches[1]) 'ListParagraph'));continue}
    if($line -match '^\d+\. '){Flush-Paragraph;[void]$body.Append((Paragraph $line 'ListParagraph'));continue}
    if([string]::IsNullOrWhiteSpace($line)){Flush-Paragraph;continue}
    if($line.StartsWith('Figure ')){Flush-Paragraph;[void]$body.Append((Paragraph $line 'Caption'));continue}
    $buffer.Add($line)
}
Flush-Paragraph;Flush-Table
[void]$body.Append('<w:sectPr><w:footerReference w:type="default" r:id="rIdFooter"/><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="864" w:right="1008" w:bottom="864" w:left="1008" w:header="360" w:footer="360"/><w:cols w:space="720"/></w:sectPr>')
$styles=@'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:color w:val="000000"/><w:sz w:val="21"/><w:lang w:val="en-CA"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="100" w:line="265" w:lineRule="auto"/><w:widowControl/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="360" w:after="220"/><w:keepNext/></w:pPr><w:rPr><w:b/><w:sz w:val="48"/><w:color w:val="000000"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="0" w:after="220"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="34"/><w:color w:val="000000"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="220" w:after="120"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/><w:color w:val="000000"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="15" w:line="235" w:lineRule="auto"/><w:ind w:left="120" w:right="120"/><w:keepLines/></w:pPr><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="17"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="200" w:hanging="200"/><w:spacing w:after="80"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="TableText"><w:name w:val="Table Text"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0" w:line="250" w:lineRule="auto"/></w:pPr><w:rPr><w:sz w:val="19"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="TableHeader"><w:name w:val="Table Header"/><w:basedOn w:val="TableText"/><w:rPr><w:b/><w:color w:val="000000"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="TableAfter"><w:name w:val="Table After"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="70" w:line="50" w:lineRule="exact"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="Caption"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="160"/></w:pPr><w:rPr><w:i/><w:sz w:val="19"/></w:rPr></w:style>
</w:styles>
'@
$document='<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>'+$body.ToString()+'</w:body></w:document>'
$footer='<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr><w:jc w:val="right"/></w:pPr><w:r><w:rPr><w:sz w:val="18"/></w:rPr><w:t xml:space="preserve">Page </w:t></w:r><w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>'
$contentTypes='<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>'
$packageRels='<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>'
$core='<?xml version="1.0" encoding="UTF-8"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>MTG Premodern Client Developer Guide</dc:title><dc:subject>Repository onboarding and one week learning plan</dc:subject><dc:creator>Project developer onboarding</dc:creator></cp:coreProperties>'
$fileStream=[System.IO.File]::Open($docxPath,[System.IO.FileMode]::Create)
$zip=[System.IO.Compression.ZipArchive]::new($fileStream,[System.IO.Compression.ZipArchiveMode]::Create,$false)
function Add-Part([string]$name,[string]$text) {
    $entry=$zip.CreateEntry($name);$stream=$entry.Open();$writer=[System.IO.StreamWriter]::new($stream,[System.Text.UTF8Encoding]::new($false));$writer.Write($text);$writer.Dispose()
}
try {
    Add-Part '[Content_Types].xml' $contentTypes
    Add-Part '_rels/.rels' $packageRels
    Add-Part 'word/document.xml' $document
    Add-Part 'word/styles.xml' $styles
    Add-Part 'word/footer1.xml' $footer
    Add-Part 'docProps/core.xml' $core
    Add-Part 'word/_rels/document.xml.rels' ('<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'+($rels -join '')+'</Relationships>')
    foreach($file in $imageFiles){$entry=$zip.CreateEntry('word/media/'+$file);$stream=$entry.Open();$bytes=[System.IO.File]::ReadAllBytes((Join-Path $out $file));$stream.Write($bytes,0,$bytes.Length);$stream.Dispose()}
}finally{$zip.Dispose();$fileStream.Dispose()}
[xml]$check=$document
if($chapter -ne 11){throw "Expected nine main sections and two appendices; found $chapter"}
if($imageFiles.Count -ne 2){throw 'Expected architecture and sequence figures'}
Write-Output "Created $docxPath; validated XML; $chapter main headings; $($imageFiles.Count) diagrams."

