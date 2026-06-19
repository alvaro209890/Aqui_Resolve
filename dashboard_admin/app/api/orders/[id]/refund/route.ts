import { NextRequest, NextResponse } from 'next/server'
import { getAdminFirestore, adminApp } from '@/lib/firebase-admin'
import * as admin from 'firebase-admin'
import { PagarmeService } from '@/lib/services/pagarme-service'

async function notify(
  db: admin.firestore.Firestore,
  userId: string,
  title: string,
  body: string,
  type: string
) {
  try {
    const tokenSnap = await db.collection('userTokens').doc(userId).get()
    const fcmToken = tokenSnap.data()?.token || tokenSnap.data()?.fcmToken
    if (fcmToken && adminApp) {
      await admin
        .messaging(adminApp)
        .send({ token: fcmToken, notification: { title, body }, data: { type } })
        .catch(() => null)
    }
    await db.collection('notifications').add({
      userId,
      title,
      message: body,
      isRead: false,
      type,
      timestamp: admin.firestore.FieldValue.serverTimestamp(),
    })
  } catch {
    // notificação não bloqueia a operação principal
  }
}

// POST /api/orders/[id]/refund — reembolsa o pagamento de um pedido via Pagar.me (Admin SDK)
// Body opcional: { amount?: number (em reais, para reembolso parcial), reason?: string }
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const db = getAdminFirestore()
    const { id } = await params
    const orderId = id

    let body: { amount?: number; reason?: string } = {}
    try {
      body = await request.json()
    } catch {
      body = {}
    }

    const orderRef = db.collection('orders').doc(orderId)
    const orderSnap = await orderRef.get()
    if (!orderSnap.exists) {
      return NextResponse.json({ success: false, error: 'Pedido não encontrado' }, { status: 404 })
    }

    const order = orderSnap.data() || {}
    const gatewayOrderId = String(order.transactionId || order.gatewayOrderId || '').trim()
    if (!gatewayOrderId) {
      return NextResponse.json(
        { success: false, error: 'Pedido sem transação Pagar.me associada (transactionId vazio)' },
        { status: 400 }
      )
    }

    if (String(order.paymentStatus || '').toLowerCase() === 'refunded') {
      return NextResponse.json(
        { success: false, error: 'Este pedido já foi reembolsado' },
        { status: 409 }
      )
    }

    const pagarme = new PagarmeService()

    // Busca o pedido na Pagar.me para localizar as cobranças (charges)
    const orderResp = await pagarme.getOrder(gatewayOrderId)
    if (orderResp.errors || !orderResp.data) {
      const msg = orderResp.errors?.[0]?.message || 'Não foi possível consultar o pedido na Pagar.me'
      return NextResponse.json({ success: false, error: msg }, { status: 502 })
    }

    const charges = Array.isArray(orderResp.data.charges) ? orderResp.data.charges : []
    const refundable = charges.filter((c) => {
      const s = String(c.status || '').toLowerCase()
      return s === 'paid' || s === 'captured'
    })

    if (refundable.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Nenhuma cobrança paga encontrada para reembolsar' },
        { status: 409 }
      )
    }

    // Reembolso parcial só faz sentido com uma única cobrança
    const partialAmountCents =
      typeof body.amount === 'number' && body.amount > 0 && refundable.length === 1
        ? PagarmeService.toCents(body.amount)
        : undefined

    const results: { chargeId: string; ok: boolean; error?: string }[] = []
    for (const charge of refundable) {
      const resp = await pagarme.refundCharge(charge.id, partialAmountCents)
      if (resp.errors) {
        results.push({ chargeId: charge.id, ok: false, error: resp.errors[0]?.message })
      } else {
        results.push({ chargeId: charge.id, ok: true })
      }
    }

    const allOk = results.every((r) => r.ok)
    const anyOk = results.some((r) => r.ok)

    if (!anyOk) {
      return NextResponse.json(
        { success: false, error: 'Falha ao reembolsar na Pagar.me', results },
        { status: 502 }
      )
    }

    // Atualiza o pedido no Firestore
    const isPartial = partialAmountCents !== undefined
    await orderRef.update({
      paymentStatus: isPartial ? 'partially_refunded' : 'refunded',
      // Encerra a pendência de reembolso aberta pelo cancelamento do cliente — sem isso
      // o app continuaria exibindo "será reembolsado em 24h" mesmo após o estorno.
      refundStatus: isPartial ? 'partial' : 'completed',
      refundedAt: admin.firestore.FieldValue.serverTimestamp(),
      refundedBy: 'admin',
      refundReason: body.reason ?? null,
      ...(isPartial ? {} : { status: 'cancelled', cancelledAt: admin.firestore.FieldValue.serverTimestamp(), cancelledBy: 'admin', cancellationReason: body.reason ?? 'Reembolso' }),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    })

    // Mantém a sessão de pagamento coerente, se existir
    await db
      .collection('payment_sessions')
      .doc(gatewayOrderId)
      .set(
        { paymentStatus: isPartial ? 'partially_refunded' : 'refunded', updatedAt: admin.firestore.FieldValue.serverTimestamp() },
        { merge: true }
      )
      .catch(() => null)

    // Notifica o cliente
    const clientId = order.clientId as string | undefined
    const svcName = (order.serviceName || order.serviceType || 'Serviço') as string
    if (clientId) {
      await notify(
        db,
        clientId,
        'Reembolso processado',
        `O pagamento do seu ${svcName} foi reembolsado.`,
        'payment'
      )
    }

    // Log de auditoria
    await db.collection('adminLogs').add({
      action: 'refund_order',
      targetId: orderId,
      targetType: 'order',
      payload: {
        gatewayOrderId,
        partial: isPartial,
        amount: body.amount ?? null,
        reason: body.reason ?? null,
        results,
      },
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    })

    return NextResponse.json({
      success: true,
      orderId,
      partial: isPartial,
      allChargesRefunded: allOk,
      results,
      message: allOk ? 'Reembolso processado com sucesso' : 'Reembolso parcial: algumas cobranças falharam',
    })
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error)
    console.error('Erro ao reembolsar pedido:', message)
    return NextResponse.json({ success: false, error: message }, { status: 500 })
  }
}
