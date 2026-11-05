data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_vpc" "ai" {
  cidr_block           = "10.45.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "hansul-ai" }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.ai.id
  cidr_block              = "10.45.1.0/24"
  map_public_ip_on_launch = true
  tags                    = { Name = "hansul-ai-public" }
}

resource "aws_internet_gateway" "ai" {
  vpc_id = aws_vpc.ai.id
  tags   = { Name = "hansul-ai" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.ai.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.ai.id
  }
  tags = { Name = "hansul-ai-public" }
  # The app stack owns the peering route in this table. Do not erase it on AI refresh.
  lifecycle { ignore_changes = [route] }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "ai" {
  name        = "hansul-ai-private"
  description = "Private bridge only; ingress granted by app stack"
  vpc_id      = aws_vpc.ai.id
  tags        = { Name = "hansul-ai-private" }
}

resource "aws_vpc_security_group_egress_rule" "outbound" {
  security_group_id = aws_security_group.ai.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_iam_role" "ai" {
  name = "hansul-ai-ssm"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ai.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "token" {
  role = aws_iam_role.ai.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["ssm:GetParameter"], Resource = "arn:aws:ssm:${var.region}:${var.account_id}:parameter/hansul/ai/token" }]
  })
}

resource "aws_iam_instance_profile" "ai" {
  name = "hansul-ai-ssm"
  role = aws_iam_role.ai.name
}

resource "aws_instance" "ai" {
  ami                         = data.aws_ssm_parameter.al2023.value
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.ai.id]
  iam_instance_profile        = aws_iam_instance_profile.ai.name
  associate_public_ip_address = true
  user_data                   = file("${path.module}/user-data.sh")
  credit_specification { cpu_credits = "standard" }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = 20
    encrypted             = true
    # Preserve the Codex login if EC2 is terminated outside the guarded script.
    # destroy.ps1 -IncludeAi explicitly deletes this exact root volume afterward.
    delete_on_termination = false
  }
  metadata_options { http_tokens = "required" }
  lifecycle { ignore_changes = [ami, user_data] }
  depends_on = [aws_route_table_association.public, aws_iam_role_policy_attachment.ssm]
  tags       = { Name = "hansul-ai" }
}
