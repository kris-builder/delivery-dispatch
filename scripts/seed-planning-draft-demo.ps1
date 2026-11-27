param(
    [ValidateRange(1, 65535)] [int]$ApiPort = 8093,
    [Parameter(Mandatory)] [PSCredential]$Dispatcher,
    [Parameter(Mandatory)] [PSCredential]$Operator
)

$ErrorActionPreference = 'Stop'
$api = "http://127.0.0.1:$ApiPort/api/v1"

function Invoke-DemoPost($session, [string]$path, $body, [string]$key) {
    $csrf = Invoke-RestMethod "$api/auth/csrf" -WebSession $session
    $headers = @{ $csrf.headerName = $csrf.token }
    if ($key) {
        $headers['Idempotency-Key'] = $key
    }
    $request = @{
        WebSession = $session
        Method = 'Post'
        Headers = $headers
        ContentType = 'application/json; charset=utf-8'
        Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8))
    }
    Invoke-RestMethod "$api$path" @request
}

function Open-DemoSession($credential) {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $account = Invoke-DemoPost $session '/employee-auth/login' @{
        loginName = $credential.UserName
        password = $credential.GetNetworkCredential().Password
    }
    if ($account.mustChangePassword) {
        throw "请先在员工入口修改 $($credential.UserName) 的临时密码。"
    }
    return $session
}

$dispatcherSession = Open-DemoSession $Dispatcher
$operatorSession = Open-DemoSession $Operator
$area = (Invoke-RestMethod "$api/staff/areas" -WebSession $dispatcherSession).items |
    Where-Object code -eq 'EAST'
if (!$area) {
    throw '查不到东片区。请确认已启动江岸演示网点。'
}

$street = '演示东路'
$community = '草案样例小区'
$rules = (Invoke-RestMethod "$api/staff/area-rules?pageSize=100" -WebSession $dispatcherSession).items
$rule = $rules | Where-Object { $_.street -ceq $street -and $_.community -ceq $community }
if (!$rule) {
    $null = Invoke-DemoPost $dispatcherSession '/staff/area-rules' @{
        street = $street; community = $community; areaId = $area.id
    }
} elseif (!$rule.active -or $rule.areaId -ne $area.id) {
    throw '样例地址已有其他规则。请核对现行规则后重新运行。'
}

$batch = Invoke-DemoPost $dispatcherSession '/staff/inbound-batches' @{
    targetNetworkCode = 'JIANGAN-DEMO'
    sourceBatchNo = 'PLANNING-03-DEMO'
    parcels = @(
        @{ waybillNo = '000726030001'; street = $street; community = $community }
        @{ waybillNo = '000726030002' }
        @{ waybillNo = '000726030003' }
    )
}
foreach ($waybill in @('000726030001', '000726030002')) {
    $null = Invoke-DemoPost $operatorSession "/staff/inbound-batches/$($batch.id)/receipts" @{
        waybillNo = $waybill
    } "planning-03-$waybill"
}
Invoke-RestMethod "$api/staff/inbound-batches/$($batch.id)" -WebSession $dispatcherSession
