from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ("tracker", "0004_locationupdate_source_gps_fields"),
    ]

    operations = [
        migrations.AlterModelOptions(
            name="locationupdate",
            options={"ordering": ["-created_at", "-timestamp", "-id"]},
        ),
    ]
