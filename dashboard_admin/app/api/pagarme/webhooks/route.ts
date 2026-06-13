import { NextRequest, NextResponse } from 'next/server'
import { PagarmeWebhook } from '@/types/pagarme'
import { PagarmeFirebaseSync } from '@/lib/services/pagarme-firebase-sync'
import { getAdminFirestore, adminApp } from '@/lib/firebase-admin'
import * as admin from 'firebase-admin'

/**
 * Notifica um usuário (FCM + persistência) — best-effort, nunca lança.
 */
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
    // notificação nunca bloqueia o webhook
  }
}

/**
 * Resolve o pedido local (coleção `orders`) a partir dos dados do webhook.
 * Tenta primeiro `metadata.order_id` (gravado pelo backend ao criar a ordem),
 * depois cai para uma busca por `transactionId == <id do gateway>`.
 */
async function resolveLocalOrder(
  db: admin.firestore.Firestore,
  data: Record<string, unknown>
): Promise<{ id: string; data: admin.firestore.DocumentData } | null> {
  try {
    const metadata = (data?.metadata as Record<string, unknown> | undefined) || undefined
    const metaOrderId = metadata?.order_id ? String(metadata.order_id) : ''
    if (metaOrderId && !metaOrderId.startsWith('cart_checkout')) {
      const snap = await db.collection('orders').doc(metaOrderId).get()
      if (snap.exists) return { id: snap.id, data: snap.data() as admin.firestore.DocumentData }
    }

    // Fallback: id do pedido no gateway (em eventos order.* é data.id; em charge.* pode ser data.order_id/order.id)
    const gatewayOrderId = String(
      (data?.id as string) ||
        (data?.order_id as string) ||
        ((data?.order as Record<string, unknown> | undefined)?.id as string) ||
        ''
    ).trim()
    if (gatewayOrderId) {
      const q = await db.collection('orders').where('transactionId', '==', gatewayOrderId).limit(1).get()
      if (!q.empty) {
        const d = q.docs[0]
        return { id: d.id, data: d.data() }
      }
    }
  } catch {
    // best-effort
  }
  return null
}

/**
 * POST /api/pagarme/webhooks
 * Recebe webhooks do Pagar.me e sincroniza com Firebase.
 *
 * IMPORTANTE: a atualização de STATUS do pedido (awaiting_payment → distributing) é feita
 * pelo backend de pagamentos (Render) em /api/payments/webhook/pagarme, que é a fonte da verdade.
 * Aqui mantemos os registros analíticos (pagarme_*) e disparamos notificações/reconciliações
 * complementares de forma best-effort (sem duplicar a escrita de status do backend).
 */
export async function POST(request: NextRequest) {
  try {
    const webhook: PagarmeWebhook = await request.json()

    console.log('📥 Webhook recebido:', {
      id: webhook.id,
      type: webhook.type,
      created_at: webhook.created_at,
    })

    // Processar o webhook baseado no tipo
    switch (webhook.type) {
      case 'charge.paid': {
        console.log('✅ Cobrança paga:', webhook.data.id)
        await PagarmeFirebaseSync.saveCharge(webhook.data)
        try {
          const db = getAdminFirestore()
          const order = await resolveLocalOrder(db, webhook.data as unknown as Record<string, unknown>)
          if (order?.data?.clientId) {
            const svc = String(order.data.serviceName || order.data.serviceType || 'serviço')
            await notify(
              db,
              String(order.data.clientId),
              'Pagamento confirmado',
              `Recebemos o pagamento do seu ${svc}. Já estamos buscando um prestador.`,
              'payment'
            )
          }
        } catch {}
        break
      }

      case 'charge.failed': {
        console.log('❌ Cobrança falhou:', webhook.data.id)
        await PagarmeFirebaseSync.saveCharge(webhook.data)
        try {
          const db = getAdminFirestore()
          const order = await resolveLocalOrder(db, webhook.data as unknown as Record<string, unknown>)
          if (order?.data?.clientId) {
            const svc = String(order.data.serviceName || order.data.serviceType || 'serviço')
            await notify(
              db,
              String(order.data.clientId),
              'Falha no pagamento',
              `Não conseguimos confirmar o pagamento do seu ${svc}. Tente novamente.`,
              'payment'
            )
          }
        } catch {}
        break
      }

      case 'charge.refunded': {
        console.log('↩️ Cobrança reembolsada:', webhook.data.id)
        await PagarmeFirebaseSync.saveCharge(webhook.data)
        // Reconcilia o pedido caso o reembolso tenha sido feito direto no painel da Pagar.me
        try {
          const db = getAdminFirestore()
          const order = await resolveLocalOrder(db, webhook.data as unknown as Record<string, unknown>)
          if (order) {
            const current = String(order.data.paymentStatus || '').toLowerCase()
            if (current !== 'refunded') {
              await db.collection('orders').doc(order.id).update({
                paymentStatus: 'refunded',
                refundedAt: admin.firestore.FieldValue.serverTimestamp(),
                refundedBy: order.data.refundedBy || 'pagarme',
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              })
            }
            if (order.data.clientId) {
              const svc = String(order.data.serviceName || order.data.serviceType || 'serviço')
              await notify(
                db,
                String(order.data.clientId),
                'Reembolso processado',
                `O pagamento do seu ${svc} foi reembolsado.`,
                'payment'
              )
            }
          }
        } catch {}
        break
      }

      case 'subscription.created':
        console.log('🔄 Assinatura criada:', webhook.data.id)
        await PagarmeFirebaseSync.saveSubscription(webhook.data)
        break

      case 'subscription.canceled':
        console.log('🚫 Assinatura cancelada:', webhook.data.id)
        await PagarmeFirebaseSync.saveSubscription(webhook.data)
        break

      case 'order.paid':
        console.log('✅ Pedido pago:', webhook.data.id)
        await PagarmeFirebaseSync.saveOrder(webhook.data)
        break

      case 'order.canceled':
        console.log('🚫 Pedido cancelado:', webhook.data.id)
        await PagarmeFirebaseSync.saveOrder(webhook.data)
        break

      default:
        console.log('ℹ️ Tipo de webhook não tratado:', webhook.type)
    }

    // Registrar log de sucesso
    await PagarmeFirebaseSync.logSync('webhook', 1, 'success', { type: webhook.type })

    // Sempre retornar 200 para o Pagar.me
    return NextResponse.json(
      {
        success: true,
        message: 'Webhook processado e sincronizado com Firebase',
      },
      { status: 200 }
    )
  } catch (error) {
    console.error('❌ Erro ao processar webhook:', error)

    // Registrar log de erro
    try {
      await PagarmeFirebaseSync.logSync('webhook', 1, 'error', { error: String(error) })
    } catch {}

    // Mesmo com erro, retornar 200 para não ficar reenviando
    return NextResponse.json(
      {
        success: false,
        error: 'Erro ao processar webhook',
      },
      { status: 200 }
    )
  }
}
