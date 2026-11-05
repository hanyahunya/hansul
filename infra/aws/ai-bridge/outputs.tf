output "instance_id" { value = aws_instance.ai.id }
output "private_ip" { value = aws_instance.ai.private_ip }
output "vpc_id" { value = aws_vpc.ai.id }
output "vpc_cidr" { value = aws_vpc.ai.cidr_block }
output "route_table_id" { value = aws_route_table.public.id }
output "security_group_id" { value = aws_security_group.ai.id }
