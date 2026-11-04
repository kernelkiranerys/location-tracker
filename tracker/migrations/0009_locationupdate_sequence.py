from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("tracker", "0008_device_location_history"),
    ]

    operations = [
        migrations.AddField(
            model_name="locationupdate",
            name="sequence",
            field=models.BigIntegerField(default=0),
        ),
        migrations.AddIndex(
            model_name="locationupdate",
            index=models.Index(fields=["device", "created_at", "sequence"], name="tracker_loc_dev_seq_idx"),
        ),
    ]
