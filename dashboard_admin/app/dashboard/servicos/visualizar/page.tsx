"use client"

import { useState, useEffect, useCallback } from "react"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog"
import { useToast } from "@/hooks/use-toast"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Search, RefreshCw, Eye, ArrowRightLeft, XCircle, ChevronLeft, ChevronRight, AlertTriangle, User, MapPin, Download } from "lucide-react"
import Link from "next/link"

const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  awaiting_payment: { label: "Aguardando Pagamento", color: "bg-yellow-100 text-yellow-800" },
  pending:          { label: "Pendente",             color: "bg-blue-100 text-blue-800" },
  distributing:     { label: "Distribuindo",         color: "bg-purple-100 text-purple-800" },
  assigned:         { label: "Atribuído",            color: "bg-indigo-100 text-indigo-800" },
  in_progress:      { label: "Em Andamento",         color: "bg-orange-100 text-orange-800" },
  completed:        { label: "Concluído",            color: "bg-green-100 text-green-800" },
  cancelled:        { label: "Cancelado",            color: "bg-red-100 text-red-800" },
}

interface Order {
  id: string
  protocol?: string
  clientName?: string
  assignedProviderName?: string
  serviceName?: string
  serviceType?: string
  address?: string
  status?: string
  estimatedPrice?: number
  createdAt?: { seconds: number }
}

interface Provider {
  id: string
  nome?: string
  name?: string
  fullName?: string
  verificationStatus?: string
}

function exportToCSV(orders: Order[]) {
  const header = ["Protocolo", "Status", "Cliente", "Prestador", "Serviço", "Endereço", "Valor (R$)", "Criado em"]
  const rows = orders.map(o => [
    o.protocol ?? o.id.slice(0, 8),
    STATUS_CONFIG[o.status ?? ""]?.label ?? o.status ?? "",
    o.clientName ?? "",
    o.assignedProviderName ?? "",
    o.serviceName ?? o.serviceType ?? "",
    o.address ?? "",
    o.estimatedPrice != null ? Number(o.estimatedPrice).toFixed(2) : "",
    o.createdAt ? new Date(o.createdAt.seconds * 1000).toLocaleDateString("pt-BR") : "",
  ])
  const csv = [header, ...rows].map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\n")
  const blob = new Blob(["﻿" + csv], { type: "text/csv;charset=utf-8" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `pedidos_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

export default function VisualizarServicosPage() {
  const [orders, setOrders] = useState<Order[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [statusFilter, setStatusFilter] = useState("all")
  const [searchInput, setSearchInput] = useState("")
  const [search, setSearch] = useState("")
  const [redirectOrder, setRedirectOrder] = useState<Order | null>(null)
  const [redirectReason, setRedirectReason] = useState("")
  const [redirecting, setRedirecting] = useState(false)
  const [cancelOrder, setCancelOrder] = useState<Order | null>(null)
  const [cancelReason, setCancelReason] = useState("")
  const [cancelling, setCancelling] = useState(false)
  const [providers, setProviders] = useState<Provider[]>([])
  const [selectedProviderId, setSelectedProviderId] = useState<string>("pool")
  const [exportingAll, setExportingAll] = useState(false)
  const { toast } = useToast()
  const LIMIT = 15

  const fetchOrders = useCallback(async () => {
    setLoading(true)
    try {
      const params = new URLSearchParams({ page: String(page), limit: String(LIMIT) })
      if (statusFilter !== "all") params.set("status", statusFilter)
      if (search) params.set("cliente", search)
      const res = await fetch(`/api/orders?${params}`)
      const data = await res.json()
      if (data.success) { setOrders(data.data ?? []); setTotal(data.pagination?.total ?? 0) }
    } catch { toast({ title: "Erro ao carregar pedidos", variant: "destructive" }) }
    finally { setLoading(false) }
  }, [page, statusFilter, search, toast])

  useEffect(() => { fetchOrders() }, [fetchOrders])

  // Carrega prestadores aprovados ao abrir o dialog de redirecionamento
  useEffect(() => {
    if (!redirectOrder) return
    fetch("/api/providers/active")
      .then(r => r.json())
      .then(d => setProviders(d.providers ?? []))
      .catch(() => null)
  }, [redirectOrder])

  async function doRedirect() {
    if (!redirectOrder || !redirectReason.trim()) return
    setRedirecting(true)
    try {
      const body: Record<string, string> = { reason: redirectReason }
      if (selectedProviderId !== "pool") {
        const prov = providers.find(p => p.id === selectedProviderId)
        body.newProviderId = selectedProviderId
        body.newProviderName = prov?.nome ?? prov?.name ?? prov?.fullName ?? selectedProviderId
      }
      const res = await fetch(`/api/orders/${redirectOrder.id}/redirect`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      })
      const data = await res.json()
      if (!data.success) throw new Error(data.error)
      toast({ title: "Pedido redirecionado", description: data.message })
      setRedirectOrder(null); setRedirectReason(""); setSelectedProviderId("pool"); fetchOrders()
    } catch (e: unknown) {
      toast({ title: "Erro", description: e instanceof Error ? e.message : String(e), variant: "destructive" })
    } finally { setRedirecting(false) }
  }

  async function doCancel() {
    if (!cancelOrder) return
    setCancelling(true)
    try {
      const res = await fetch(`/api/orders/${cancelOrder.id}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "cancelled", cancelledBy: "admin", cancellationReason: cancelReason }),
      })
      const data = await res.json()
      if (!data.success) throw new Error(data.error)
      toast({ title: "Pedido cancelado." })
      setCancelOrder(null); setCancelReason(""); fetchOrders()
    } catch (e: unknown) {
      toast({ title: "Erro", description: e instanceof Error ? e.message : String(e), variant: "destructive" })
    } finally { setCancelling(false) }
  }

  async function handleExportAll() {
    setExportingAll(true)
    try {
      const res = await fetch(`/api/orders?limit=1000&page=1`)
      const data = await res.json()
      if (data.success) exportToCSV(data.data ?? [])
      else throw new Error(data.error)
    } catch {
      toast({ title: "Erro ao exportar", variant: "destructive" })
    } finally { setExportingAll(false) }
  }

  const totalPages = Math.ceil(total / LIMIT)

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Serviços</h1>
          <p className="text-sm text-muted-foreground">{total} pedido(s)</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={handleExportAll} disabled={exportingAll} className="gap-2">
            {exportingAll ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />} Exportar CSV
          </Button>
          <Button variant="outline" size="sm" onClick={fetchOrders} disabled={loading} className="gap-2">
            <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> Atualizar
          </Button>
        </div>
      </div>

      <div className="flex gap-3 flex-wrap">
        <div className="flex-1 min-w-[200px] relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input className="pl-9" placeholder="Buscar cliente, protocolo…" value={searchInput}
            onChange={e => setSearchInput(e.target.value)}
            onKeyDown={e => { if (e.key === "Enter") { setSearch(searchInput); setPage(1) } }} />
        </div>
        <Select value={statusFilter} onValueChange={v => { setStatusFilter(v); setPage(1) }}>
          <SelectTrigger className="w-52"><SelectValue placeholder="Status" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">Todos os status</SelectItem>
            {Object.entries(STATUS_CONFIG).map(([k, v]) => <SelectItem key={k} value={k}>{v.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      <Card>
        <CardContent className="p-0">
          {loading ? (
            <div className="flex items-center justify-center h-40"><RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" /></div>
          ) : orders.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 gap-2">
              <AlertTriangle className="h-6 w-6 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">Nenhum pedido encontrado</p>
            </div>
          ) : (
            <div className="divide-y">
              {orders.map(order => {
                const sc = STATUS_CONFIG[order.status ?? ""] ?? { label: order.status ?? "—", color: "bg-muted text-muted-foreground" }
                const canRedirect = ["assigned", "in_progress"].includes(order.status ?? "")
                const canCancel = !["completed", "cancelled"].includes(order.status ?? "")
                return (
                  <div key={order.id} className="flex items-start gap-4 px-4 py-3 hover:bg-muted/30">
                    <div className="flex-1 min-w-0 space-y-0.5">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-medium">{order.protocol ?? order.id.slice(0, 8)}</span>
                        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${sc.color}`}>{sc.label}</span>
                      </div>
                      <div className="flex items-center gap-3 text-xs text-muted-foreground flex-wrap">
                        <span className="flex items-center gap-1"><User className="h-3 w-3" />{order.clientName ?? "—"}</span>
                        <span className="flex items-center gap-1"><User className="h-3 w-3" />{order.assignedProviderName ?? "Sem prestador"}</span>
                        <span>{order.serviceName ?? order.serviceType ?? "—"}</span>
                        {order.estimatedPrice != null && <span>R$ {Number(order.estimatedPrice).toFixed(2)}</span>}
                      </div>
                      {order.address && <p className="text-xs text-muted-foreground flex items-center gap-1"><MapPin className="h-3 w-3" />{order.address}</p>}
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <Button variant="ghost" size="icon" className="h-7 w-7" asChild title="Ver OS">
                        <Link href={`/dashboard/servicos/os/${order.id}`}><Eye className="h-4 w-4" /></Link>
                      </Button>
                      {canRedirect && (
                        <Button variant="ghost" size="icon" className="h-7 w-7 text-amber-600" title="Redirecionar"
                          onClick={() => { setRedirectOrder(order); setRedirectReason(""); setSelectedProviderId("pool") }}>
                          <ArrowRightLeft className="h-4 w-4" />
                        </Button>
                      )}
                      {canCancel && (
                        <Button variant="ghost" size="icon" className="h-7 w-7 text-red-500" title="Cancelar"
                          onClick={() => { setCancelOrder(order); setCancelReason("") }}>
                          <XCircle className="h-4 w-4" />
                        </Button>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </CardContent>
      </Card>

      {totalPages > 1 && (
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">Página {page} de {totalPages}</span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => setPage(p => p - 1)} disabled={page <= 1}><ChevronLeft className="h-4 w-4" /></Button>
            <Button variant="outline" size="sm" onClick={() => setPage(p => p + 1)} disabled={page >= totalPages}><ChevronRight className="h-4 w-4" /></Button>
          </div>
        </div>
      )}

      {/* Dialog de Redirecionamento — agora com opção de direcionar para prestador específico */}
      <Dialog open={!!redirectOrder} onOpenChange={open => !open && setRedirectOrder(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle className="flex items-center gap-2"><ArrowRightLeft className="h-5 w-5 text-amber-600" />Redirecionar Serviço</DialogTitle></DialogHeader>
          <div className="space-y-3 py-2">
            <p className="text-sm text-muted-foreground">Prestador atual: <strong>{redirectOrder?.assignedProviderName}</strong></p>

            <div className="space-y-1">
              <label className="text-sm font-medium">Direcionar para</label>
              <Select value={selectedProviderId} onValueChange={setSelectedProviderId}>
                <SelectTrigger>
                  <SelectValue placeholder="Selecione o destino" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="pool">Devolver ao pool de distribuição</SelectItem>
                  {providers.map(p => (
                    <SelectItem key={p.id} value={p.id}>
                      {p.nome ?? p.name ?? p.fullName ?? p.id}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1">
              <label className="text-sm font-medium">Motivo (obrigatório)</label>
              <Textarea placeholder="Ex: Prestador não compareceu" value={redirectReason} onChange={e => setRedirectReason(e.target.value)} rows={3} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRedirectOrder(null)}>Cancelar</Button>
            <Button onClick={doRedirect} disabled={redirecting || !redirectReason.trim()} className="gap-2">
              {redirecting && <RefreshCw className="h-4 w-4 animate-spin" />}
              {selectedProviderId === "pool" ? "Devolver ao Pool" : "Redirecionar"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!cancelOrder} onOpenChange={open => !open && setCancelOrder(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle className="flex items-center gap-2"><XCircle className="h-5 w-5 text-red-500" />Cancelar Pedido</DialogTitle></DialogHeader>
          <div className="space-y-3 py-2">
            <p className="text-sm text-muted-foreground">Cancelar pedido <strong>{cancelOrder?.protocol}</strong>. Esta ação não pode ser desfeita.</p>
            <div className="space-y-1">
              <label className="text-sm font-medium">Motivo (opcional)</label>
              <Textarea placeholder="Ex: Solicitação do cliente" value={cancelReason} onChange={e => setCancelReason(e.target.value)} rows={2} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelOrder(null)}>Voltar</Button>
            <Button variant="destructive" onClick={doCancel} disabled={cancelling} className="gap-2">
              {cancelling && <RefreshCw className="h-4 w-4 animate-spin" />} Confirmar Cancelamento
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
