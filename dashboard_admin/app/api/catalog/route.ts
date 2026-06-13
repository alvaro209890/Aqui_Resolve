import { NextRequest, NextResponse } from 'next/server'
import { getAdminFirestore } from '@/lib/firebase-admin'
import * as admin from 'firebase-admin'

// Catálogo de serviços do app (service_categories + service_types).
// Escrita exclusiva via Admin SDK — as Firestore rules bloqueiam escrita pelo client SDK,
// evitando que qualquer usuário autenticado do app altere o catálogo global.
// A leitura (lista/real-time) continua sendo feita pelo painel via client SDK (onSnapshot).

function normalizeSlug(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

interface CatalogInput {
  id?: string
  name?: string
  slug?: string
  description?: string
  active?: boolean
  displayOrder?: number
  icon?: string
  aliases?: string[]
}

// POST /api/catalog — cria ou atualiza (quando `id` é enviado) um serviço do catálogo.
export async function POST(request: NextRequest) {
  try {
    const db = getAdminFirestore()
    const input = (await request.json()) as CatalogInput

    const name = String(input.name ?? '').trim()
    if (!name) {
      return NextResponse.json({ success: false, error: 'Nome do serviço é obrigatório' }, { status: 400 })
    }

    const slug = normalizeSlug(input.slug || name)
    const order = Number.isFinite(Number(input.displayOrder)) ? Number(input.displayOrder) : 0
    const aliases = Array.isArray(input.aliases)
      ? input.aliases.map((a) => String(a).trim()).filter(Boolean)
      : []
    const active = input.active !== false

    const payload = {
      name,
      title: name,
      label: name,
      slug,
      description: String(input.description ?? '').trim(),
      active,
      isActive: active,
      enabled: active,
      displayOrder: order,
      order,
      sortOrder: order,
      icon: String(input.icon ?? '').trim() || 'wrench',
      aliases,
      keywords: aliases,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }

    const id = input.id?.trim() || db.collection('service_categories').doc().id
    const isNew = !input.id?.trim()

    const docPayload = isNew
      ? { ...payload, createdAt: admin.firestore.FieldValue.serverTimestamp() }
      : payload

    await db.collection('service_categories').doc(id).set(docPayload, { merge: true })
    await db.collection('service_types').doc(id).set(docPayload, { merge: true })

    return NextResponse.json({ success: true, id })
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error)
    console.error('Erro ao salvar catálogo:', message)
    return NextResponse.json({ success: false, error: message }, { status: 500 })
  }
}

// DELETE /api/catalog?id=xxx — remove um serviço do catálogo das duas coleções.
export async function DELETE(request: NextRequest) {
  try {
    const db = getAdminFirestore()
    const id = request.nextUrl.searchParams.get('id')?.trim()
    if (!id) {
      return NextResponse.json({ success: false, error: 'id é obrigatório' }, { status: 400 })
    }

    await db.collection('service_categories').doc(id).delete()
    await db
      .collection('service_types')
      .doc(id)
      .delete()
      .catch(() => undefined)

    return NextResponse.json({ success: true, id })
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error)
    console.error('Erro ao remover do catálogo:', message)
    return NextResponse.json({ success: false, error: message }, { status: 500 })
  }
}
