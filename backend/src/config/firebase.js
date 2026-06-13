const admin = require('firebase-admin');

const { loadEnv } = require('./env');
const logger = require('../utils/logger');

function initializeFirebase() {
  if (admin.apps.length > 0) {
    return admin;
  }

  const config = loadEnv();

  if (!config.firebaseProjectId || !config.firebaseClientEmail || !config.firebasePrivateKey) {
    logger.warn('Firebase Admin SDK não inicializado — credenciais ausentes (FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, FIREBASE_PRIVATE_KEY)');
    return admin;
  }

  admin.initializeApp({
    credential: admin.credential.cert({
      projectId: config.firebaseProjectId,
      clientEmail: config.firebaseClientEmail,
      privateKey: config.firebasePrivateKey
    }),
    projectId: config.firebaseProjectId
  });

  logger.info('Firebase Admin inicializado', {
    projectId: config.firebaseProjectId
  });

  return admin;
}

async function verifyFirebaseToken(idToken) {
  const firebaseAdmin = initializeFirebase();
  return firebaseAdmin.auth().verifyIdToken(idToken);
}

module.exports = {
  initializeFirebase,
  verifyFirebaseToken
};
