from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("tracker", "0001_initial"),
    ]

    operations = [
        migrations.RemoveField(
            model_name="device",
            name="push_endpoint",
        ),
        migrations.AddField(
            model_name="pendingrequest",
            name="fulfilled_at",
            field=models.DateTimeField(blank=True, null=True),
        ),
    ]
