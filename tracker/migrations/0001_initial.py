from django.db import migrations, models
import django.db.models.deletion
import uuid


class Migration(migrations.Migration):
    initial = True

    dependencies = []

    operations = [
        migrations.CreateModel(
            name='Device',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('device_id', models.UUIDField(default=uuid.uuid4, editable=False, unique=True)),
                ('name', models.CharField(max_length=100)),
                ('secret_key', models.CharField(max_length=64)),
                ('push_endpoint', models.URLField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('last_seen', models.DateTimeField(blank=True, null=True)),
                ('privacy_until', models.DateTimeField(blank=True, null=True)),
            ],
        ),
        migrations.CreateModel(
            name='LocationUpdate',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('accuracy', models.FloatField(blank=True, null=True)),
                ('altitude', models.FloatField(blank=True, null=True)),
                ('speed', models.FloatField(blank=True, null=True)),
                ('bearing', models.FloatField(blank=True, null=True)),
                ('timestamp', models.DateTimeField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('triggered_by', models.CharField(choices=[('scheduled', 'Scheduled (20-min)'), ('on_demand', 'Server-requested'), ('privacy_override', 'Server overrode privacy mode')], default='scheduled', max_length=20)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='locations', to='tracker.device')),
            ],
        ),
        migrations.CreateModel(
            name='WifiNetwork',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('ssid', models.CharField(blank=True, max_length=100)),
                ('bssid', models.CharField(blank=True, max_length=17)),
                ('signal_dbm', models.IntegerField(blank=True, null=True)),
                ('frequency_mhz', models.IntegerField(blank=True, null=True)),
                ('is_connected', models.BooleanField(default=False)),
                ('channel', models.IntegerField(blank=True, null=True)),
                ('location_update', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='wifi_networks', to='tracker.locationupdate')),
            ],
        ),
        migrations.CreateModel(
            name='UsedNonce',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('value', models.CharField(db_index=True, max_length=64, unique=True)),
                ('expires_at', models.DateTimeField()),
            ],
        ),
        migrations.CreateModel(
            name='PendingRequest',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('fulfilled', models.BooleanField(default=False)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='pending_requests', to='tracker.device')),
            ],
        ),
        migrations.AddIndex(
            model_name='locationupdate',
            index=models.Index(fields=['device', '-timestamp'], name='tracker_locat_device__e0b1f7_idx'),
        ),
        migrations.AddIndex(
            model_name='usednonce',
            index=models.Index(fields=['expires_at'], name='tracker_usedn_expires_7b3cdd_idx'),
        ),
    ]
