function log(level, message, meta) {
  const prefix = `[${new Date().toISOString()}] [${level}]`;
  if (meta === undefined) {
    console.log(prefix, message);
    return;
  }
  console.log(prefix, message, meta);
}

module.exports = {
  info: (message, meta) => log('info', message, meta),
  warn: (message, meta) => log('warn', message, meta),
  error: (message, meta) => log('error', message, meta)
};
