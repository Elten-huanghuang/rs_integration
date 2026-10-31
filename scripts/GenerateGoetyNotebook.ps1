$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$w=568; $h=360
$bmp=[System.Drawing.Bitmap]::new($w,$h)
$rng=[System.Random]::new(43)
for($y=0;$y -lt $h;$y++){
  for($x=0;$x -lt $w;$x++){
    $edgeLeft=7+[int](2*[Math]::Sin($y*.13)+[Math]::Sin($y*.7))
    $edgeRight=$w-8+[int](2*[Math]::Sin($y*.11))
    $edgeTop=8+[int](2*[Math]::Sin($x*.08))
    $edgeBottom=$h-8+[int](2*[Math]::Sin($x*.17))
    if($x -lt $edgeLeft -or $x -gt $edgeRight -or $y -lt $edgeTop -or $y -gt $edgeBottom){continue}
    $edge=[Math]::Min([Math]::Min($x-$edgeLeft,$edgeRight-$x),[Math]::Min($y-$edgeTop,$edgeBottom-$y))
    $wear=19*[Math]::Exp(-$edge/8.0)
    $noise=$rng.Next(-5,6)+2*[Math]::Sin($x*.09+$y*.02)
    $r=[int](244-$wear+$noise); $gr=[int](230-$wear*1.6+$noise); $b=[int](194-$wear*2+$noise)
    $bmp.SetPixel($x,$y,[System.Drawing.Color]::FromArgb(255,[Math]::Clamp($r,0,255),[Math]::Clamp($gr,0,255),[Math]::Clamp($b,0,255)))
  }
}
$g=[System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode=[System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$pen=[System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(28,115,84,42),1)
for($i=0;$i -lt 70;$i++){
 $x=$rng.Next(15,$w-15);$y=$rng.Next(15,$h-15)
 $g.DrawLine($pen,$x,$y,$x+$rng.Next(3,13),$y+1)
}
$g.Dispose();$pen.Dispose()
$out=Join-Path $PSScriptRoot '..\src\main\resources\assets\rs_integration\textures\gui'
[System.IO.Directory]::CreateDirectory($out) | Out-Null
# Downsample for a crisp Minecraft-scale resource.
$small=[System.Drawing.Bitmap]::new(284,180)
$sg=[System.Drawing.Graphics]::FromImage($small)
$sg.DrawImage($bmp,0,0,284,180)
$small.Save([System.IO.Path]::GetFullPath((Join-Path $out 'notebook.png')),[System.Drawing.Imaging.ImageFormat]::Png)
$sg.Dispose();$small.Dispose();$bmp.Dispose()
