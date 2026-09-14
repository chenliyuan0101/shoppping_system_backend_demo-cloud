# =====================================================================
# P8-4：**每服务一条构建流水线**（本机可用脚本模拟；不用 Jenkins/GitLab 那套重家伙）
#
# 设计要点（都是本项目踩出来的教训，不是拍脑袋）：
#   1. **只做 `test`，不默认打包**：Windows 上运行中的服务**锁着 jar** ⇒ `package` 会以
#      "Unable to rename ... .jar to .jar.original" 失败（我在 mall-product 上实测踩过）。
#      要打包就先停服务（`-Package` 会**明确拒绝**并告诉你为什么，而不是给你一个半截的失败）。
#   2. **构建必须串行**：多 agent/多终端同时跑 Maven 会互相抢 `target/` 与本地仓库 ⇒
#      本脚本开跑前检查"是否已有 Maven/surefire 在跑"，有就等（最多等 N 秒）。
#   3. **`-o` 离线**：本机依赖已齐；在线会引入与代码无关的失败（网络抖动）。
#   4. **如实汇报**：逐服务打印 `Tests run: …` 原始行；任何一个服务非 0 退出，脚本最后以非 0 结束。
#
# 用法：
#   powershell -File backend\mall-cloud\build.ps1                 # 全部服务（串行）
#   powershell -File backend\mall-cloud\build.ps1 -Service mall-admin
#   powershell -File backend\mall-cloud\build.ps1 -List            # 只列出服务与当前是否在跑
# =====================================================================
param(
    [string]$Service = '',
    [switch]$List,
    [int]$WaitForBuildSeconds = 300
)
$ErrorActionPreference = 'Stop'
$root = 'D:\My_project\shopping_system2'
$cloud = Join-Path $root 'backend\mall-cloud'
$mvn = 'D:\maven\apache-maven-3.9.11\bin\mvn.cmd'
$env:JAVA_HOME = 'D:\java compiler\jdk21'

# 服务名 → 端口（用于"是否在跑"的判断与提示）
$services = [ordered]@{
    'mall-gateway'     = 8000
    # P8-2 起：单体已更名 mall-trade 并归位到 mall-cloud/mall-trade/（注册名 mall-trade、库 mall_trade）
    #   ⇒ 它现在是**普通的第 9 个模块**，不再是"还没独立模块"的那个例外。
    'mall-trade'       = 8080
    'mall-user-center' = 8101
    'mall-product'     = 8102
    'mall-search'      = 8103
    'mall-review'      = 8104
    'mall-marketing'   = 8106
    'mall-content'     = 8107
    'mall-admin'       = 8108
}
# 还没有独立模块的：**空表**（P8-2 之后 9 个模块齐了）。
#   ⚠️ 这张表曾经写过 `mall-trade`（"等 P8-2 搬完才有独立模块"）—— 那行在 P8-2 完成后就是**过期信息**，
#      会让 CI 把 mall-trade 当"没有模块"直接 SKIP（本轮复核时抓到并清掉）。
$notYetModules = @{}

function Test-PortUp([int]$p) { [bool](netstat -ano | Select-String ":$p\s+.*LISTENING") }
function Get-ConcurrentBuild {
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and ($_.CommandLine -match 'classworlds\.launcher\.Launcher|surefirebooter') -and ($_.CommandLine -notmatch 'idea\.maven|RemoteMavenServer') })
}

if ($List) {
    Write-Output "服务            端口   模块存在   在跑"
    foreach ($k in $services.Keys) {
        $dir = Join-Path $cloud $k
        "{0,-18}{1,-7}{2,-11}{3}" -f $k, $services[$k], (Test-Path (Join-Path $dir 'pom.xml')), (Test-PortUp $services[$k])
    }
    foreach ($k in $notYetModules.Keys) { "{0,-16}{1,-7}{2,-11}{3}" -f $k, '-', '否', '（无独立进程）' ; Write-Output ("    ↳ " + $notYetModules[$k]) }
    exit 0
}

$targets = if ($Service) { @($Service) } else { @($services.Keys) }
foreach ($t in $targets) {
    if ($notYetModules.Contains($t)) { Write-Output "SKIP  $t —— $($notYetModules[$t])"; continue }
    if (-not $services.Contains($t)) { Write-Output "FATAL 未知服务名：$t（用 -List 看清单）"; exit 2 }
}

# 串行纪律：等别人跑完
$waited = 0
while ((Get-ConcurrentBuild).Count -gt 0 -and $waited -lt $WaitForBuildSeconds) {
    Write-Output "  等待其它构建结束…（已等 ${waited}s）"
    Start-Sleep -Seconds 10; $waited += 10
}
if ((Get-ConcurrentBuild).Count -gt 0) { Write-Output "FATAL 仍有构建在跑（等满 ${WaitForBuildSeconds}s）—— 不并发跑，退出"; exit 3 }

$results = @()
foreach ($t in $targets) {
    $dir = Join-Path $cloud $t
    $jar = Get-ChildItem (Join-Path $dir 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
    $running = $false
    foreach ($p in $services[$t]) { }
    $running = Test-PortUp $services[$t]
    Write-Output "==================== $t（端口 $($services[$t])$(if ($running) { '，正在运行' })）===================="
    Push-Location $dir
    # ⚠️ 踩过的坑：本脚本 `$ErrorActionPreference='Stop'` 时，**原生命令的 stderr 会被当成终止性错误**
    #    （Maven 往 stderr 打一句 Mockito 自附加的警告 ⇒ 脚本在"还没拿到 Tests run"就中断，exit=1）。
    #    所以调用期间临时放成 'Continue'，只看 `$LASTEXITCODE`；拿完立刻恢复。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $mvn -o test 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    Pop-Location
    $counts = @($out | Select-String -Pattern '^\[INFO\] Tests run: .*Skipped' | Select-Object -Last 1) +
              @($out | Select-String -Pattern '^\[ERROR\] Tests run: .*Skipped' | Select-Object -Last 1)
    $line = if ($counts.Count) { ($counts | Select-Object -Last 1).Line.Trim() } else { '(未见 Tests run 汇总行 —— 可能是编译失败)' }
    $build = @($out | Select-String -Pattern 'BUILD SUCCESS|BUILD FAILURE' | Select-Object -Last 1)
    $buildLine = if ($build.Count) { $build[0].Line.Trim() } else { '(未见 BUILD 行)' }
    # 失败明细（只取前几条，避免刷屏）
    $errs = @($out | Select-String -Pattern '^\[ERROR\]   \w' | Select-Object -First 5 | ForEach-Object { $_.Line.Trim() })
    Write-Output "  $line"
    Write-Output "  $buildLine"
    foreach ($e in $errs) { Write-Output "    $e" }
    if ($running) { Write-Output "  ⚠️ 该服务正在运行 ⇒ 本轮只做了 test（不打包：jar 被进程锁着，Windows 上 rename 会失败）" }
    $results += [pscustomobject]@{ Service = $t; Exit = $code; Counts = $line; Build = $buildLine }
}

Write-Output ''
Write-Output '==================== 汇总 ===================='
$results | ForEach-Object { "{0,-16} exit={1}  {2}" -f $_.Service, $_.Exit, $_.Counts }
$bad = @($results | Where-Object { $_.Exit -ne 0 })
if ($bad.Count -eq 0) { Write-Output "PIPELINE OK（$($results.Count) 个服务，全部 0 失败 0 错误）" ; exit 0 }
Write-Output "PIPELINE FAIL（$($bad.Count)/$($results.Count) 个服务非 0 退出）"
exit 1
