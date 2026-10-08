# Deploying to AWS (Terraform)

Nothing here has been applied. No AWS resources exist until you run the steps below. The configuration passes
`terraform fmt` and `terraform validate`; it has not been planned, because planning needs AWS credentials.

## What it builds

One VPC per environment in two zones, with three kinds of subnet:

- public: the load balancer and the NAT gateways
- private: api and worker tasks (outbound only, through NAT)
- isolated: the renderer and the database. Their route table has no default route.

Services (ECS Fargate): `api` behind an HTTPS load balancer (HTTPS to the tasks as well), `worker`, and `renderer`.
The renderer accepts connections only from the worker. Its security group has egress only to the ECR/CloudWatch Logs
VPC endpoints and the S3 prefix list, which Fargate needs to pull the image and ship logs.
Strictly, this is endpoint-only egress, not "no egress rules" as PHASE6_SPEC.md 2.1 reads: a Fargate task cannot start without them.
Also: RDS PostgreSQL 16 (TLS required, master password managed by RDS in Secrets Manager), a versioned encrypted S3
bucket for user files, a private S3 bucket behind CloudFront for the single-page app, ACM certificates, Route 53
records, SES domain identity with DKIM, and CloudWatch alarms.

Environments are Terraform workspaces named `dev` and `prod` (any other name fails at plan time). dev is smaller,
single-AZ database, one NAT, and sign-in links go to the log (`APP_MAIL_MODE=log`) so `scripts/smoke-test.ps1` can sign in.
prod uses SES, a Multi-AZ database and deletion protection. The application refuses to start in `prod` with log mail.

## Steps

1. One-time state bucket: `cd infra/bootstrap; terraform init; terraform apply`.
2. `cd infra; terraform init -backend-config="bucket=<state bucket>" -backend-config="region=us-east-1"`.
3. `terraform workspace new dev`, copy `dev.tfvars.example` to `dev.tfvars` and fill it in.
4. Create the ECR repositories first: `terraform apply -var-file=dev.tfvars -target=aws_ecr_repository.web -target=aws_ecr_repository.renderer`.
5. `.\scripts\deploy-images.ps1 -Env dev -Tag dev-1`, then set `image_tag` to match.
6. `terraform apply -var-file=dev.tfvars`.
7. Put the real secrets in (Terraform only creates placeholders and ignores the values afterwards):
   `aws secretsmanager put-secret-value --secret-id tailor-dev/anthropic-api-key --secret-string <key>`
   and the same for `tailor-dev/google-oauth-client-secret`. Then restart the services (`aws ecs update-service --force-new-deployment`).
8. Upload the single-page app to the `spa_bucket` output and invalidate CloudFront.
9. SES starts in the sandbox; request production access in the SES console before inviting real users.
10. `.\scripts\smoke-test.ps1 -BaseUrl https://dev-api.<domain> -MailFrom cloudwatch -LogGroup /tailor/dev/api`.

Prod is the same with `terraform workspace new prod` and `prod.tfvars`. Do not create it before the dev smoke test passes
and the estimate below is approved.

## Monthly cost estimate (us-east-1, on-demand list prices, 730 hours; approximate)

Prices used: Fargate $0.04048 per vCPU-hour and $0.004445 per GB-hour, NAT gateway $0.045/h, interface endpoint
$0.01/h per zone, public IPv4 $0.005/h, ALB about $22/month with light traffic. They come from AWS public price
pages as I know them, not from a live Price List query; check the AWS pricing calculator before relying on them.

| Item | dev | prod |
|---|---|---|
| Fargate api (dev 1 x 0.5 vCPU/1 GB; prod 2 x 1 vCPU/2 GB) | $18 | $72 |
| Fargate worker (dev 1 x 1/3 GB; prod 2 x 2/4 GB) | $39 | $144 |
| Fargate renderer (dev 1 x 1/2 GB; prod 2 x 2/4 GB) | $36 | $144 |
| NAT gateways (1 / 2) | $33 | $66 |
| VPC interface endpoints (3 x 1 zone / 3 x 2 zones) | $22 | $44 |
| Load balancer | $22 | $22 |
| RDS PostgreSQL (t4g.micro single-AZ 20 GB / t4g.small Multi-AZ 50 GB) | $14 | $58 |
| Public IPv4 addresses | $11 | $15 |
| Secrets Manager (3 secrets) | $1 | $1 |
| CloudWatch logs, Container Insights, S3, CloudFront, Route 53 records, misc. | $5 | $15 |
| **Total** | **about $200** | **about $580** |

Not included: Anthropic API usage (per generation and match), NAT and internet data transfer (about $0.045/GB), SES
(about $0.10 per 1,000 emails), and the hosted zone ($0.50/month) if you do not already have one.
dev can be torn down when idle (`terraform destroy`), which stops almost all of its cost.
The largest levers for prod are the worker and renderer sizes ($288 together) and the second NAT ($33).
