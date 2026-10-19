SELECT 'inventory_5m' AS dataset, COUNT(*) AS rows_count, COUNT(DISTINCT station_id) AS stations,
       MIN(observed_slot) AS first_slot, MAX(observed_slot) AS last_slot FROM station_inventory_5m;
SELECT 'od_5m' AS dataset,COUNT(*) AS rows_count,MIN(service_date) AS first_date,MAX(service_date) AS last_date,
       COUNT(DISTINCT service_date) AS available_days,SUM(usage_minutes IS NULL) AS unknown_metrics FROM station_flow_5m;
SELECT service_date,COUNT(*) AS rows_count,COUNT(DISTINCT time_slot) AS time_slots FROM station_flow_5m GROUP BY service_date ORDER BY service_date;
SELECT dataset_id,resolution,COUNT(*) AS stored_rows,MIN(period_date) AS first_date,MAX(period_date) AS last_date,
       SUM(rent_count) AS rental_total FROM station_usage GROUP BY dataset_id,resolution ORDER BY dataset_id;
SELECT source_key,status,source_rows,stored_rows,error_code FROM collection_run ORDER BY source_key;
SELECT COUNT(*) AS stations_with_official_number FROM station WHERE station_number IS NOT NULL;
SELECT dataset_id,COUNT(*) AS usage_rows,SUM(station_id IS NOT NULL) AS mapped_rows FROM station_usage_with_id GROUP BY dataset_id;
SELECT period_date,COUNT(DISTINCT hour_of_day) AS stored_hours,MIN(hour_of_day) AS first_hour,
       MAX(hour_of_day) AS last_hour,COUNT(*) AS station_hour_rows,SUM(rent_count) AS hourly_rental_total
FROM station_usage WHERE dataset_id='OA-15245' AND resolution='hour' GROUP BY period_date;
SELECT h.period_date,h.hourly_total,d.daily_total,h.hourly_total-d.daily_total AS difference
FROM (SELECT period_date,SUM(rent_count) AS hourly_total FROM station_usage WHERE dataset_id='OA-15245' GROUP BY period_date) h
JOIN (SELECT period_date,SUM(rent_count) AS daily_total FROM station_usage WHERE dataset_id='OA-15246' GROUP BY period_date) d
ON h.period_date=d.period_date;
