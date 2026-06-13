const dotenv = require('dotenv');

dotenv.config();

let cachedEnv = null;

function parseBoolean(value, defaultValue = false) {
  if (value === undefined || value === null || value === '') {
    return defaultValue;
  }

  return ['1', 'true', 'yes', 'on'].includes(String(value).trim().toLowerCase());
}

function parseInteger(value, defaultValue) {
  const parsed = Number.parseInt(String(value), 10);
  return Number.isNaN(parsed) ? defaultValue : parsed;
}

function getEnv(name, options = {}) {
  const {
    required = true,
    defaultValue = undefined
  } = options;

  const rawValue = process.env[name];

  if (rawValue === undefined || rawValue === null || rawValue === '') {
    if (required) {
      throw new Error(`Missing required environment variable: ${name}`);
    }

    return defaultValue;
  }

  return rawValue;
}

function loadEnv() {
  if (cachedEnv) {
    return cachedEnv;
  }

  const port = parseInteger(getEnv('PORT', { required: false, defaultValue: '3000' }), 3000);
  const nodeEnv = getEnv('NODE_ENV', { required: false, defaultValue: 'development' });
  const renderExternalUrl = getEnv('RENDER_EXTERNAL_URL', {
    required: false,
    defaultValue: ''
  });
  const keepAliveIntervalMs = parseInteger(
    getEnv('KEEP_ALIVE_INTERVAL_MS', {
      required: false,
      defaultValue: '840000'
    }),
    840000
  );

  cachedEnv = {
    nodeEnv,
    port,
    pagarmeBaseUrl: getEnv('PAGARME_BASE_URL', {
      required: false,
      defaultValue: 'https://api.pagar.me/core/v5'
    }),
    pagarmeSecretKey: getEnv('PAGARME_SECRET_KEY', { required: false, defaultValue: '' }),
    firebaseProjectId: getEnv('FIREBASE_PROJECT_ID', { required: false, defaultValue: '' }),
    firebaseClientEmail: getEnv('FIREBASE_CLIENT_EMAIL', { required: false, defaultValue: '' }),
    firebasePrivateKey: (getEnv('FIREBASE_PRIVATE_KEY', { required: false, defaultValue: '' }) || '').replace(/\\n/g, '\n'),
    corsOrigin: getEnv('CORS_ORIGIN', { required: false, defaultValue: '*' }),
    keepAliveEnabled: parseBoolean(
      getEnv('KEEP_ALIVE_ENABLED', {
        required: false,
        defaultValue: nodeEnv === 'production' ? 'true' : 'false'
      }),
      nodeEnv === 'production'
    ),
    keepAliveUrl: getEnv('KEEP_ALIVE_URL', {
      required: false,
      defaultValue: renderExternalUrl
    }),
    keepAliveIntervalMs: Math.max(60000, keepAliveIntervalMs)
  };

  return cachedEnv;
}

module.exports = {
  loadEnv
};
