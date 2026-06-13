/**
 * Semeia o catálogo de serviços (nichos) em `service_categories` e `service_types`
 * a partir dos nichos canônicos atuais do app, com apelidos (aliases/keywords) para o matching.
 *
 * Idempotente: usa o slug como id determinístico e, se já existir um doc com o mesmo nome
 * (normalizado), atualiza esse doc em vez de criar duplicado. Faz merge — não apaga campos
 * editados no painel além dos que ele próprio escreve.
 *
 * Como rodar (a partir de dashboard_admin/, que tem firebase-admin e .env.local):
 *   node scripts/seed-catalog.mjs
 */

import admin from 'firebase-admin'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

// Escapa quebras de linha reais dentro de strings JSON (mesma lógica do lib/firebase-admin.ts).
function escapeNewlinesInsideJsonStrings(value) {
  let fixed = ''
  let inString = false
  let escaped = false
  for (const char of value) {
    if (escaped) {
      fixed += char
      escaped = false
    } else if (char === '\\' && inString) {
      fixed += char
      escaped = true
    } else if (char === '"') {
      inString = !inString
      fixed += char
    } else if (char === '\n' && inString) {
      fixed += '\\n'
    } else {
      fixed += char
    }
  }
  return fixed
}

// --- Lê o service account do .env.local (suporta JSON com \" e \n escapados) ---
function loadServiceAccount() {
  let value = process.env.FIREBASE_SERVICE_ACCOUNT
  if (!value) {
    const envPath = resolve(__dirname, '..', '.env.local')
    const raw = readFileSync(envPath, 'utf8')
    const line = raw.split(/\r?\n/).find((l) => l.startsWith('FIREBASE_SERVICE_ACCOUNT='))
    if (!line) throw new Error('FIREBASE_SERVICE_ACCOUNT não encontrado em .env.local')
    value = line.slice('FIREBASE_SERVICE_ACCOUNT='.length).trim()
  }

  const trimmed = value.trim()

  // Várias estratégias: objeto direto; duplo-encode (string JSON contendo JSON, formato do .env.local);
  // e a lógica tolerante do lib/firebase-admin.ts (escape de \" e quebras reais).
  const attempts = [
    () => JSON.parse(trimmed),
    () => {
      // Formato do .env.local: string JSON contendo JSON, com quebras reais dentro das strings
      // após o primeiro parse → re-escapar antes do segundo parse.
      const s = JSON.parse(trimmed)
      if (typeof s !== 'string') return s
      return JSON.parse(escapeNewlinesInsideJsonStrings(s))
    },
    () => {
      const cands = [trimmed]
      if (trimmed.includes('\\"')) cands.push(trimmed.replace(/\\"/g, '"'))
      for (const c of cands) {
        try {
          return JSON.parse(escapeNewlinesInsideJsonStrings(c))
        } catch {
          /* próximo */
        }
      }
      throw new Error('fallback')
    },
  ]

  for (const attempt of attempts) {
    try {
      const parsed = attempt()
      if (parsed && typeof parsed === 'object' && parsed.private_key) {
        parsed.private_key = parsed.private_key.replace(/\\n/g, '\n')
        return parsed
      }
    } catch {
      // tenta a próxima estratégia
    }
  }
  throw new Error('Não foi possível parsear FIREBASE_SERVICE_ACCOUNT')
}

function normalizeKey(value) {
  return value
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ')
}

function slugify(value) {
  return value
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

// Nichos canônicos + apelidos (espelham as heurísticas do ServiceNicheCatalog.kt)
const NICHES = [
  { name: 'Elétrica', aliases: ['eletrica', 'disjuntor', 'tomada', 'chuveiro', 'lampada', 'interruptor', 'resistencia', 'luminaria', 'spots'] },
  { name: 'Encanador', aliases: ['hidraulica', 'encanamento', 'torneira', 'rabicho', 'sifao', 'registro', 'vazamentos', 'descarga', 'caixa acoplada'] },
  { name: 'Instalação', aliases: ['suporte de tv', 'ventilador', 'cooktop', 'purificador', 'lava louca', 'maquina de lavar', 'varal', 'gas'] },
  { name: "Caixa d'água", aliases: ['caixa d agua', 'caixa dagua', 'boia', 'limpeza de caixa'] },
  { name: 'Desentupimento manual', aliases: ['desentupimento de pia', 'desentupimento ralo', 'desentupimento vaso'] },
  { name: 'Desentupimento com maquinário até 2 m', aliases: ['desentupimento maquinario', 'maquinario'] },
  { name: 'Caça-vazamentos', aliases: ['caca vazamento', 'caca vazamentos', 'caca-vazamentos'] },
  { name: 'Limpeza de estofados', aliases: ['estofados', 'sofa', 'colchao', 'tapete', 'carpete', 'poltrona', 'impermeabilizacao', 'higienizacao'] },
  { name: 'Ar condicionado', aliases: ['ar-condicionado', 'climatizacao', 'split', 'btus'] },
  { name: 'Eletrodomésticos', aliases: ['eletrodomesticos', 'micro-ondas', 'micro ondas', 'fogao', 'forno', 'geladeira'] },
  { name: 'Chaveiro residencial', aliases: ['chaveiro', 'fechadura', 'chave', 'abertura de portas'] },
  { name: 'Serviços automotivos', aliases: ['automotivo', 'pneu', 'combustivel', 'pane seca', 'partida eletrica', 'chave de veiculo'] },
  { name: 'Montagem de móveis', aliases: ['montagem', 'moveis', 'guarda roupa', 'escrivaninha', 'armario', 'comoda', 'prateleiras', 'cama', 'mesa'] },
  { name: 'Faxina', aliases: ['faxina', 'limpeza', 'pos-obra', 'diarista'] },
]

async function main() {
  const serviceAccount = loadServiceAccount()
  if (admin.apps.length === 0) {
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) })
  }
  const db = admin.firestore()

  // Mapa de docs existentes por nome normalizado (evita duplicados).
  const existingByName = new Map()
  const existingSnap = await db.collection('service_categories').get()
  existingSnap.forEach((doc) => {
    const d = doc.data() || {}
    const name = String(d.name ?? d.title ?? d.label ?? '').trim()
    if (name) existingByName.set(normalizeKey(name), doc.id)
  })

  let created = 0
  let updated = 0

  for (let i = 0; i < NICHES.length; i++) {
    const niche = NICHES[i]
    const slug = slugify(niche.name)
    const displayOrder = i + 1
    const payload = {
      name: niche.name,
      title: niche.name,
      label: niche.name,
      slug,
      description: '',
      active: true,
      isActive: true,
      enabled: true,
      displayOrder,
      order: displayOrder,
      sortOrder: displayOrder,
      icon: 'wrench',
      aliases: niche.aliases,
      keywords: niche.aliases,
      updatedAt: admin.firestore.Timestamp.now(),
    }

    const existingId = existingByName.get(normalizeKey(niche.name))
    if (existingId) {
      await db.collection('service_categories').doc(existingId).set(payload, { merge: true })
      await db.collection('service_types').doc(existingId).set(payload, { merge: true })
      updated++
      console.log(`~ atualizado: ${niche.name} (${existingId})`)
    } else {
      const ref = db.collection('service_categories').doc(slug)
      await ref.set({ ...payload, createdAt: admin.firestore.Timestamp.now() }, { merge: true })
      await db.collection('service_types').doc(slug).set({ ...payload, createdAt: admin.firestore.Timestamp.now() }, { merge: true })
      created++
      console.log(`+ criado: ${niche.name} (${slug})`)
    }
  }

  console.log(`\nConcluído. Criados: ${created}, Atualizados: ${updated}, Total nichos: ${NICHES.length}`)
  process.exit(0)
}

main().catch((err) => {
  console.error('Erro ao semear catálogo:', err)
  process.exit(1)
})
