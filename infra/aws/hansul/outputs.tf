output "instance_id" { value = aws_instance.app.id }
output "public_ip" { value = aws_instance.app.public_ip }
output "data_volume_id" { value = aws_ebs_volume.data.id }
output "deployment_bucket" { value = aws_s3_bucket.deploy.id }
