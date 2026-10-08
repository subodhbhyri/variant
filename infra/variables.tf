variable "aws_region" {
  description = "Region for everything except the CloudFront certificate (which must be in us-east-1)."
  type        = string
  default     = "us-east-1"
}

variable "domain_name" {
  description = "A domain hosted in Route 53 in this account, e.g. example.com. The app is served at <app_subdomain>.<domain_name> and the api at <api_subdomain>.<domain_name> (dev environments get a 'dev-' prefix)."
  type        = string
}

variable "route53_zone_id" {
  description = "The Route 53 hosted zone of domain_name. Certificates, the load balancer, CloudFront and the SES DKIM records are created and validated in it."
  type        = string
}

variable "app_subdomain" {
  type    = string
  default = "app"
}

variable "api_subdomain" {
  type    = string
  default = "api"
}

variable "image_tag" {
  description = "The tag of the tailor-web and tailor-renderer images in ECR (scripts/deploy-images.ps1 pushes them)."
  type        = string
}

variable "google_client_id" {
  description = "OAuth client id for Sign in with Google (not a secret). Empty turns Google sign-in off."
  type        = string
  default     = ""
}

variable "alarm_email" {
  description = "Where alarms are sent. Empty creates the alarms without a subscriber."
  type        = string
  default     = ""
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}
