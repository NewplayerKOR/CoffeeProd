export function approvedSshBaseUrl(value) {
  const url = value || 'http://127.0.0.1:18080';
  if (!['http://127.0.0.1:18080', 'http://127.0.0.1:18081', 'http://127.0.0.1:18082'].includes(url)) {
    throw new Error('Only the explicitly prepared shared, Windows or Git localhost SSH target is allowed.');
  }
  return url;
}
