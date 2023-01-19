
resource "aws_iam_role" "default_instance_role" {
  name = "${var.tags.useCase}-${var.tags.owner}-instance-role"

  # Terraform's "jsonencode" function converts a
  # Terraform expression result to valid JSON syntax.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Sid    = ""
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })

}


resource "aws_iam_instance_profile" "default_profile" {

  name = "${var.tags.useCase}-${var.tags.owner}-instance-profile"
  role = aws_iam_role.default_instance_role.name

}


