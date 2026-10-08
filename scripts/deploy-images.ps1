# Builds the web and renderer images and pushes them to the ECR repositories of one environment.
#   .\scripts\deploy-images.ps1 -Env dev -Tag dev-1
# Needs the AWS CLI logged in (aws sso login / configured credentials) and Docker. Tags are immutable in ECR, so
# every deployment uses a new tag; then set image_tag in the tfvars file and run terraform apply.
param(
    [Parameter(Mandatory = $true)][ValidateSet('dev', 'prod')][string]$Env,
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Region = 'us-east-1'
)
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path -Parent $PSScriptRoot)

$account = (aws sts get-caller-identity --query Account --output text) | Out-String
$account = $account.Trim()
if (-not $account) { throw 'No AWS credentials: run aws configure or aws sso login first.' }

$registry = "$account.dkr.ecr.$Region.amazonaws.com"
$password = [string](aws ecr get-login-password --region $Region)
$password | docker login --username AWS --password-stdin $registry
if ($LASTEXITCODE -ne 0) { throw 'docker login to ECR failed' }

$web = "$registry/tailor-$Env-web:$Tag"
$renderer = "$registry/tailor-$Env-renderer:$Tag"

docker build -f docker/web.Dockerfile -t $web .
if ($LASTEXITCODE -ne 0) { throw 'web image build failed' }
docker build -f docker/Dockerfile --target renderer -t $renderer .
if ($LASTEXITCODE -ne 0) { throw 'renderer image build failed' }

docker push $web
if ($LASTEXITCODE -ne 0) { throw 'web push failed' }
docker push $renderer
if ($LASTEXITCODE -ne 0) { throw 'renderer push failed' }

Write-Host "Pushed $web and $renderer. Set image_tag = `"$Tag`" in $Env.tfvars and run terraform apply."
