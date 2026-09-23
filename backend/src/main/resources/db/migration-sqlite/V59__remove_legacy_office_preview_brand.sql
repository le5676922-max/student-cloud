UPDATE system_config
SET value = ''
WHERE name = 'onlyOfficeUrl'
  AND value IN (
    'http://office.' || 'z' || 'file.vip',
    'https://office.' || 'z' || 'file.vip'
  );
