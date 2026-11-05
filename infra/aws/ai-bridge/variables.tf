variable "region" { type = string }
variable "profile" { type = string }
variable "account_id" { type = string }
variable "instance_type" {
  type    = string
  default = "t3.small"
}
