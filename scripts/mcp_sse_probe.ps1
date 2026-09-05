$ErrorActionPreference = "Stop"
$base = "http://localhost:8082"
$sseOut = "D:\ClaudeCode\AgentProduct\agent-platform\logs\mcp_sse.tmp"
Remove-Item $sseOut -ErrorAction SilentlyContinue

# 1) keep SSE connection in background
$job = Start-Job -ScriptBlock {
    param($url, $out)
    curl.exe -sN --max-time 20 $url -H "X-Tenant-Id: default" -H "X-Api-Key: dev-key" -o $out
} -ArgumentList "http://localhost:8082/mcp/sse", $sseOut

Start-Sleep -Seconds 3
$raw = Get-Content $sseOut -Raw -ErrorAction SilentlyContinue
Write-Host "== SSE =="
Write-Host $raw
if ($raw -notmatch 'event:endpoint') { Write-Host "!! no endpoint event"; exit 1 }

if ($raw -match 'data:(/mcp/message\?sessionId=[0-9a-f-]+)') { $msgEndpoint = $matches[1] } else { exit 1 }
Write-Host "== msg endpoint: $msgEndpoint =="

# 2) POST tools/list; response arrives via SSE stream
$body = '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
Write-Host "== POST tools/list =="
curl.exe -sN --max-time 8 -w "`nHTTP_CODE:%{http_code}`n" "$base$msgEndpoint" -H "X-Tenant-Id: default" -H "X-Api-Key: dev-key" -H "Content-Type: application/json" -d $body

Start-Sleep -Seconds 4
$report = Get-Content $sseOut -Raw -ErrorAction SilentlyContinue
Write-Host "== SSE full =="
Write-Host $report

Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue