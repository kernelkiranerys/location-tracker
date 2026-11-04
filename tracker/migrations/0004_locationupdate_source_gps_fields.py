from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("tracker", "0003_alter_locationupdate_options_and_more"),
    ]

    operations = [
        migrations.AddField(
            model_name="locationupdate",
            name="location_source",
            field=models.CharField(
                choices=[
                    ("google_wifi", "Google WiFi geolocation"),
                    ("gps", "Client GPS fix (fallback)"),
                ],
                default="gps",
                max_length=20,
            ),
        ),
        migrations.AddField(
            model_name="locationupdate",
            name="gps_latitude",
            field=models.FloatField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name="locationupdate",
            name="gps_longitude",
            field=models.FloatField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name="locationupdate",
            name="gps_accuracy",
            field=models.FloatField(blank=True, null=True),
        ),
    ]
