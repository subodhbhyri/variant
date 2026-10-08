# Logs carry ids, codes, counts and timings only: never resume, notes, posting or edit text (PHASE6_SPEC.md 9.3).

resource "aws_cloudwatch_log_group" "service" {
  for_each          = toset(["api", "worker", "renderer"])
  name              = "/tailor/${local.env}/${each.key}"
  retention_in_days = local.size.log_retention_days
}
