import { NextResponse } from 'next/server'
import { getAdminFirestore } from '@/lib/firebase-admin'

export const dynamic = 'force-dynamic'

// GET /api/providers/active — lista prestadores aprovados e ativos para seleção
export async function GET() {
  try {
    const db = getAdminFirestore()

    const snap = await db.collection('providers')
      .where('verificationStatus', '==', 'approved')
      .limit(300)
      .get()

    const providers = snap.docs.map(doc => {
      const d = doc.data()
      return {
        id: doc.id,
        nome: d.nome || d.name || d.fullName || '',
        phone: d.phone || d.telefone || '',
        services: d.services || d.specialties || [],
        isActive: d.isActive ?? d.ativo ?? true,
        rating: d.rating ?? null,
      }
    }).filter(p => p.isActive !== false)
      .sort((a, b) => (a.nome as string).localeCompare(b.nome as string, 'pt-BR'))

    return NextResponse.json({ success: true, providers, count: providers.length })
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error)
    return NextResponse.json({ success: false, error: message, providers: [] }, { status: 500 })
  }
}
