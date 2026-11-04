from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("tracker", "0009_locationupdate_sequence"),
    ]

    operations = [
        migrations.CreateModel(
            name="AppLog",
            fields=[
                ("id", models.BigAutoField(primary_key=True, serialize=False)),
                ("timestamp", models.DateTimeField(help_text="Client-side log timestamp")),
                ("level", models.CharField(max_length=10, default="INFO")),
                ("tag", models.CharField(max_length=50, default="app")),
                ("message", models.TextField()),
                ("received_at", models.DateTimeField(auto_now_add=True)),
                ("device", models.ForeignKey(
                    on_delete=models.CASCADE,
                    related_name="app_logs",
                    to="tracker.device",
                )),
            ],
            options={
                "ordering": ["-timestamp"],
            },
        ),
        migrations.AddIndex(
            model_name="applog",
            index=models.Index(fields=["device", "-timestamp"], name="tracker_applog_dev_ts_idx"),
        ),
        migrations.AddIndex(
            model_name="applog",
            index=models.Index(fields=["level"], name="tracker_applog_level_idx"),
        ),
        migrations.AddIndex(
            model_name="applog",
            index=models.Index(fields=["tag"], name="tracker_applog_tag_idx"),
        ),
    ]
