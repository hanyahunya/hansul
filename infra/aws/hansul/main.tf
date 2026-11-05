data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

data "http" "cloudflare_ipv4" {
  url = "https://www.cloudflare.com/ips-v4/"
}

locals {
  cloudflare_ipv4 = toset([for cidr in split("\n", data.http.cloudflare_ipv4.response_body) : trimspace(cidr) if trimspace(cidr) != ""])
}

resource "aws_vpc" "app" {
  cidr_block           = "10.44.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "hansul-app" }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.app.id
  cidr_block              = "10.44.1.0/24"
  map_public_ip_on_launch = true
  tags                    = { Name = "hansul-app-public" }
}

resource "aws_internet_gateway" "app" {
  vpc_id = aws_vpc.app.id
  tags   = { Name = "hansul-app" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.app.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.app.id
  }
  tags = { Name = "hansul-app-public" }
  # The peering route is a separate aws_route resource; preserve it on refresh.
  lifecycle { ignore_changes = [route] }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_vpc_peering_connection" "ai" {
  vpc_id      = aws_vpc.app.id
  peer_vpc_id = var.ai_vpc_id
  auto_accept = true
  tags        = { Name = "hansul-app-to-ai" }
}

resource "aws_route" "app_to_ai" {
  route_table_id            = aws_route_table.public.id
  destination_cidr_block    = var.ai_vpc_cidr
  vpc_peering_connection_id = aws_vpc_peering_connection.ai.id
}

resource "aws_route" "ai_to_app" {
  route_table_id            = var.ai_route_table_id
  destination_cidr_block    = aws_vpc.app.cidr_block
  vpc_peering_connection_id = aws_vpc_peering_connection.ai.id
}

resource "aws_security_group" "app" {
  name        = "hansul-origin"
  description = "Cloudflare HTTPS only; no SSH"
  vpc_id      = aws_vpc.app.id
  tags        = { Name = "hansul-origin" }
}

resource "aws_vpc_security_group_ingress_rule" "cloudflare_https" {
  for_each          = local.cloudflare_ipv4
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Cloudflare HTTPS origin"
}

resource "aws_vpc_security_group_egress_rule" "outbound" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_vpc_security_group_ingress_rule" "ai_from_app" {
  security_group_id            = var.ai_security_group_id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8765
  to_port                      = 8765
  description                  = "Hansul app to private bridge"
  depends_on                   = [aws_vpc_peering_connection.ai]
}

resource "aws_s3_bucket" "deploy" {
  bucket = "hansul-deploy-${var.account_id}-${var.region}"
  # The guarded destroy.ps1 confirms backups and data deletion explicitly.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "deploy" {
  bucket                  = aws_s3_bucket.deploy.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "deploy" {
  bucket = aws_s3_bucket.deploy.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "deploy" {
  bucket = aws_s3_bucket.deploy.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_iam_role" "app" {
  name = "hansul-app-ssm"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "app" {
  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "${aws_s3_bucket.deploy.arn}/*" },
      { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = [
        "arn:aws:ssm:${var.region}:${var.account_id}:parameter/hansul/app/*",
        "arn:aws:ssm:${var.region}:${var.account_id}:parameter/hansul/ai/token"
      ] }
    ]
  })
}

resource "aws_iam_instance_profile" "app" {
  name = "hansul-app-ssm"
  role = aws_iam_role.app.name
}

resource "aws_instance" "app" {
  ami                         = data.aws_ssm_parameter.al2023.value
  instance_type               = var.app_instance_type
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.app.id]
  iam_instance_profile        = aws_iam_instance_profile.app.name
  associate_public_ip_address = true
  user_data                   = file("${path.module}/user-data.sh")
  root_block_device {
    volume_type           = "gp3"
    volume_size           = 20
    encrypted             = true
    delete_on_termination = true
  }
  metadata_options { http_tokens = "required" }
  lifecycle { ignore_changes = [ami, user_data] }
  depends_on = [aws_route_table_association.public, aws_iam_role_policy_attachment.ssm]
  tags       = { Name = "hansul-app" }
}

resource "aws_ebs_volume" "data" {
  availability_zone = aws_instance.app.availability_zone
  size              = 8
  type              = "gp3"
  encrypted         = true
  tags              = { Name = "hansul-data" }
}

resource "aws_volume_attachment" "data" {
  device_name                    = "/dev/sdf"
  volume_id                      = aws_ebs_volume.data.id
  instance_id                    = aws_instance.app.id
  stop_instance_before_detaching = true
}
