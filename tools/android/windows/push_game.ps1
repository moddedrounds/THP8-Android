# Copy the extracted game to the Thor. adb cannot create directories inside Android/data by itself
# (remote secure_mkdirs: Operation not permitted), so create the whole tree with `mkdir -p` first.
$adb = $(if ($env:ADB) { $env:ADB } else { 'adb' })
$pkg = 'org.project8recomp.android'
$src = 'C:\thp8-work\game'
$dst = "/sdcard/Android/data/$pkg/files/game"
$tmp = Join-Path $env:TEMP 'mkdirs.sh'

cmd /c "`"$adb`" shell rm -rf $dst" | Out-Null
$dirs = Get-ChildItem $src -Recurse -Directory | ForEach-Object { $_.FullName.Substring($src.Length).Replace('\','/') }
$lines = @("mkdir -p '$dst'") + ($dirs | ForEach-Object { "mkdir -p '$dst$_'" })
[IO.File]::WriteAllText($tmp, (($lines -join "`n") + "`n"))
"directories to create: $($dirs.Count)"
cmd /c "`"$adb`" push `"$tmp`" /data/local/tmp/mkdirs.sh > nul 2>&1"
cmd /c "`"$adb`" shell sh /data/local/tmp/mkdirs.sh"

$log = Join-Path $env:TEMP 'adb_push.log'
$sw = [Diagnostics.Stopwatch]::StartNew()
cmd /c "`"$adb`" push `"$src\.`" $dst/ > `"$log`" 2>&1"
"push exit=$LASTEXITCODE after $([math]::Round($sw.Elapsed.TotalSeconds)) s"
Get-Content $log -Tail 3
cmd /c "`"$adb`" shell du -sh $dst"
cmd /c "`"$adb`" shell find $dst -type f | Measure-Object -Line | ForEach-Object { `"files on device: `$(`$_.Lines)`" }"
