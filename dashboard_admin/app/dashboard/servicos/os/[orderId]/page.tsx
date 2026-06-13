"use client"

import { useState, useEffect } from "react"
import { useParams } from "next/navigation"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import {
  CheckCircle, XCircle, Clock, MapPin, User, FileText, Camera,
  PenTool, RefreshCw, ArrowLeft, AlertTriangle, Navigation
} from "lucide-react"
import Link from "next/link"

interface ChecklistData {
  orderId: string
  status: string
  startLatitude?: number
  startLongitude?: number
  startedAt?: { seconds: number }
  clientPresent?: boolean
  executedAsRequested?: boolean
  additionalService?: boolean
  serviceCompleted?: boolean
  cleanAfterService?: boolean
  executionDescription?: string
  photosBefore?: string[]
  photosDuring?: string[]
  photosAfter?: string[]
  providerSignatureUrl?: string
  clientSignatureUrl?: string
  providerName?: string
  clientName?: string
  completedAt?: { seconds: number }
}

interface OrderData {
  id: string
  protocol?: string
  serviceName?: string
  clientName?: string
  assignedProviderName?: string
  address?: string
  status?: string
  estimatedPrice?: number
  providerCommission?: number
}

function BoolField({ label, value }: { label: string; value?: boolean }) {
  if (value === undefined || value === null) return (
    <div className="flex items-center justify-between py-1">
      <span className="text-sm text-muted-foreground">{label}</span>
      <Badge variant="secondary" className="text-xs">Não respondido</Badge>
    </div>
  )
  return (
    <div className="flex items-center justify-between py-1">
      <span className="text-sm">{label}</span>
      {value
        ? <span className="flex items-center gap-1 text-green-600 text-sm"><CheckCircle className="h-4 w-4" />Sim</span>
        : <span className="flex items-center gap-1 text-red-500 text-sm"><XCircle className="h-4 w-4" />Não</span>}
    </div>
  )
}

function PhotoGrid({ urls, label }: { urls?: string[]; label: string }) {
  if (!urls?.length) return (
    <div className="text-sm text-muted-foreground italic">Sem fotos — {label.toLowerCase()}</div>
  )
  return (
    <div>
      <p className="text-xs text-muted-foreground mb-2">{label} ({urls.length})</p>
      <div className="grid grid-cols-3 gap-2">
        {urls.map((url, i) => (
          <a key={i} href={url} target="_blank" rel="noopener noreferrer">
            <img src={url} alt={`${label} ${i + 1}`} className="w-full h-24 object-cover rounded-lg border hover:opacity-80 transition-opacity" />
          </a>
        ))}
      </div>
    </div>
  )
}

export default function OsDetailPage() {
  const params = useParams()
  const orderId = params.orderId as string
  const [checklist, setChecklist] = useState<ChecklistData | null>(null)
  const [order, setOrder] = useState<OrderData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetch(`/api/checklists/${orderId}`)
      .then(r => r.json())
      .then(data => {
        if (!data.success) throw new Error(data.error)
        setChecklist(data.checklist)
        setOrder(data.order)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [orderId])

  if (loading) return (
    <div className="flex items-center justify-center h-64">
      <RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" />
    </div>
  )

  if (error) return (
    <div className="flex flex-col items-center justify-center h-64 gap-3">
      <AlertTriangle className="h-8 w-8 text-destructive" />
      <p className="text-sm text-muted-foreground">{error}</p>
    </div>
  )

  const ts = (t?: { seconds: number }) => t ? new Date(t.seconds * 1000).toLocaleString("pt-BR") : "—"

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" asChild>
          <Link href="/dashboard/servicos/visualizar"><ArrowLeft className="h-4 w-4" /></Link>
        </Button>
        <div>
          <h1 className="text-xl font-bold tracking-tight">
            OS — {order?.protocol ?? orderId}
          </h1>
          <p className="text-sm text-muted-foreground">{order?.serviceName ?? "Serviço"}</p>
        </div>
      </div>

      {/* Resumo do pedido */}
      {order && (
        <Card>
          <CardHeader><CardTitle className="text-base flex items-center gap-2"><FileText className="h-4 w-4" />Dados do Pedido</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-3 text-sm">
            <div><p className="text-xs text-muted-foreground">Cliente</p><p className="font-medium">{order.clientName ?? "—"}</p></div>
            <div><p className="text-xs text-muted-foreground">Prestador</p><p className="font-medium">{order.assignedProviderName ?? "—"}</p></div>
            <div><p className="text-xs text-muted-foreground">Endereço</p><p className="font-medium">{order.address ?? "—"}</p></div>
            <div><p className="text-xs text-muted-foreground">Status</p><Badge variant="outline">{order.status ?? "—"}</Badge></div>
            <div><p className="text-xs text-muted-foreground">Valor</p><p className="font-medium">R$ {order.estimatedPrice?.toFixed(2) ?? "—"}</p></div>
            <div><p className="text-xs text-muted-foreground">Comissão prestador</p><p className="font-medium">R$ {order.providerCommission?.toFixed(2) ?? "—"}</p></div>
          </CardContent>
        </Card>
      )}

      {!checklist ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12 gap-3">
            <Clock className="h-8 w-8 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">Checklist ainda não preenchido para esta OS</p>
          </CardContent>
        </Card>
      ) : (
        <>
          {/* Início do serviço */}
          <Card>
            <CardHeader><CardTitle className="text-base flex items-center gap-2"><Navigation className="h-4 w-4" />Início do Serviço</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              <div className="flex justify-between"><span className="text-muted-foreground">Iniciado em</span><span>{ts(checklist.startedAt)}</span></div>
              {checklist.startLatitude && checklist.startLongitude && (
                <div className="flex justify-between items-center">
                  <span className="text-muted-foreground flex items-center gap-1"><MapPin className="h-3 w-3" />GPS registrado</span>
                  <a
                    href={`https://maps.google.com/?q=${checklist.startLatitude},${checklist.startLongitude}`}
                    target="_blank" rel="noopener noreferrer"
                    className="text-primary text-xs underline"
                  >
                    {checklist.startLatitude.toFixed(6)}, {checklist.startLongitude.toFixed(6)}
                  </a>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Checklist */}
          <Card>
            <CardHeader><CardTitle className="text-base flex items-center gap-2"><CheckCircle className="h-4 w-4" />Checklist</CardTitle></CardHeader>
            <CardContent className="divide-y">
              <BoolField label="Cliente presente?" value={checklist.clientPresent} />
              <BoolField label="Serviço executado conforme solicitado?" value={checklist.executedAsRequested} />
              <BoolField label="Necessitou material adicional?" value={checklist.additionalService} />
              <BoolField label="Serviço concluído?" value={checklist.serviceCompleted} />
              <BoolField label="Local limpo após execução?" value={checklist.cleanAfterService} />
            </CardContent>
          </Card>

          {/* Relatório */}
          {checklist.executionDescription && (
            <Card>
              <CardHeader><CardTitle className="text-base flex items-center gap-2"><FileText className="h-4 w-4" />Relatório do Prestador</CardTitle></CardHeader>
              <CardContent>
                <p className="text-sm whitespace-pre-wrap">{checklist.executionDescription}</p>
              </CardContent>
            </Card>
          )}

          {/* Fotos */}
          <Card>
            <CardHeader><CardTitle className="text-base flex items-center gap-2"><Camera className="h-4 w-4" />Evidências Fotográficas</CardTitle></CardHeader>
            <CardContent className="space-y-4">
              <PhotoGrid urls={checklist.photosBefore} label="Antes" />
              <Separator />
              <PhotoGrid urls={checklist.photosDuring} label="Durante" />
              <Separator />
              <PhotoGrid urls={checklist.photosAfter} label="Depois" />
            </CardContent>
          </Card>

          {/* Assinaturas */}
          <Card>
            <CardHeader><CardTitle className="text-base flex items-center gap-2"><PenTool className="h-4 w-4" />Assinaturas</CardTitle></CardHeader>
            <CardContent className="grid grid-cols-2 gap-6">
              <div>
                <p className="text-xs text-muted-foreground mb-2 flex items-center gap-1"><User className="h-3 w-3" />Prestador</p>
                {checklist.providerSignatureUrl
                  ? <img src={checklist.providerSignatureUrl} alt="Assinatura prestador" className="border rounded-lg w-full h-32 object-contain bg-white" />
                  : <div className="border rounded-lg w-full h-32 flex items-center justify-center text-xs text-muted-foreground">Não assinado</div>}
              </div>
              <div>
                <p className="text-xs text-muted-foreground mb-2 flex items-center gap-1"><User className="h-3 w-3" />Cliente</p>
                {checklist.clientSignatureUrl
                  ? <img src={checklist.clientSignatureUrl} alt="Assinatura cliente" className="border rounded-lg w-full h-32 object-contain bg-white" />
                  : <div className="border rounded-lg w-full h-32 flex items-center justify-center text-xs text-muted-foreground">Não assinado</div>}
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
