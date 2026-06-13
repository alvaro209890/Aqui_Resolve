"use client"

import { useEffect, useMemo, useState } from "react"
import {
  collection,
  onSnapshot,
} from "firebase/firestore"
import { db } from "@/lib/firebase"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Badge } from "@/components/ui/badge"
import { Switch } from "@/components/ui/switch"
import { PageWithBack } from "@/components/layout/page-with-back"
import {
  AppWindow,
  ArrowUpDown,
  CheckCircle2,
  Loader2,
  Pencil,
  Plus,
  Search,
  ShieldCheck,
  Trash2,
  Wrench,
  XCircle,
} from "lucide-react"

interface ServiceCategoryDoc {
  id: string
  name: string
  slug: string
  description: string
  active: boolean
  displayOrder: number
  icon: string
  aliases: string[]
  createdAt?: Date
  updatedAt?: Date
}

interface FormState {
  name: string
  slug: string
  description: string
  active: boolean
  displayOrder: string
  icon: string
  aliases: string
}

const EMPTY_FORM: FormState = {
  name: "",
  slug: "",
  description: "",
  active: true,
  displayOrder: "0",
  icon: "wrench",
  aliases: "",
}

function toDate(value: unknown): Date | undefined {
  if (value && typeof (value as { toDate?: () => Date }).toDate === "function") {
    return (value as { toDate: () => Date }).toDate()
  }
  if (value instanceof Date) {
    return value
  }
  return undefined
}

function normalizeSlug(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
}

function parseAliases(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean)
}

export default function CatalogoAppPage() {
  const [services, setServices] = useState<ServiceCategoryDoc[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [search, setSearch] = useState("")
  const [editingId, setEditingId] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY_FORM)

  useEffect(() => {
    if (!db) {
      setLoading(false)
      return
    }

    const unsubscribe = onSnapshot(
      collection(db, "service_categories"),
      (snapshot) => {
        const next = snapshot.docs
          .map((snapshotDoc) => {
            const data = snapshotDoc.data()
            return {
              id: snapshotDoc.id,
              name: String(data.name ?? data.title ?? data.label ?? "Serviço"),
              slug: String(data.slug ?? normalizeSlug(String(data.name ?? data.title ?? data.label ?? snapshotDoc.id))),
              description: String(data.description ?? ""),
              active: Boolean(data.active ?? data.isActive ?? data.enabled ?? true),
              displayOrder: Number(data.displayOrder ?? data.order ?? data.sortOrder ?? 0),
              icon: String(data.icon ?? "wrench"),
              aliases: Array.isArray(data.aliases) ? data.aliases.map(String) : [],
              createdAt: toDate(data.createdAt),
              updatedAt: toDate(data.updatedAt),
            } satisfies ServiceCategoryDoc
          })
          .sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name, "pt-BR"))

        setServices(next)
        setLoading(false)
      },
      () => setLoading(false)
    )

    return () => unsubscribe()
  }, [])

  const filteredServices = useMemo(() => {
    const needle = search.trim().toLowerCase()
    if (!needle) return services
    return services.filter((service) =>
      service.name.toLowerCase().includes(needle) ||
      service.slug.toLowerCase().includes(needle) ||
      service.description.toLowerCase().includes(needle) ||
      service.aliases.some((alias) => alias.toLowerCase().includes(needle))
    )
  }, [search, services])

  const activeCount = services.filter((service) => service.active).length

  const resetForm = () => {
    setForm(EMPTY_FORM)
    setEditingId(null)
  }

  const fillForm = (service: ServiceCategoryDoc) => {
    setEditingId(service.id)
    setForm({
      name: service.name,
      slug: service.slug,
      description: service.description,
      active: service.active,
      displayOrder: String(service.displayOrder),
      icon: service.icon,
      aliases: service.aliases.join(", "),
    })
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!form.name.trim()) {
      return
    }

    const name = form.name.trim()
    const slug = normalizeSlug(form.slug || form.name)
    const displayOrder = Number(form.displayOrder || 0)
    const aliases = parseAliases(form.aliases)

    setSaving(true)
    try {
      const res = await fetch("/api/catalog", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: editingId || undefined,
          name,
          slug,
          description: form.description.trim(),
          active: form.active,
          displayOrder: Number.isFinite(displayOrder) ? displayOrder : 0,
          icon: form.icon.trim() || "wrench",
          aliases,
        }),
      })
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        alert(`Erro ao salvar serviço: ${data.error ?? res.statusText}`)
        return
      }
      resetForm()
    } finally {
      setSaving(false)
    }
  }

  const handleToggleActive = async (service: ServiceCategoryDoc) => {
    const res = await fetch("/api/catalog", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: service.id,
        name: service.name,
        slug: service.slug,
        description: service.description,
        active: !service.active,
        displayOrder: service.displayOrder,
        icon: service.icon,
        aliases: service.aliases,
      }),
    })
    if (!res.ok) {
      const data = await res.json().catch(() => ({}))
      alert(`Erro ao atualizar serviço: ${data.error ?? res.statusText}`)
    }
  }

  const handleDelete = async (service: ServiceCategoryDoc) => {
    if (!confirm(`Remover "${service.name}" do catálogo do aplicativo?`)) {
      return
    }
    const res = await fetch(`/api/catalog?id=${encodeURIComponent(service.id)}`, {
      method: "DELETE",
    })
    if (!res.ok) {
      const data = await res.json().catch(() => ({}))
      alert(`Erro ao remover serviço: ${data.error ?? res.statusText}`)
      return
    }
    if (editingId === service.id) {
      resetForm()
    }
  }

  return (
    <PageWithBack backButtonLabel="Voltar para Serviços">
      <div className="space-y-6">
        <Card className="overflow-hidden border-border/70 bg-[radial-gradient(circle_at_top_left,_rgba(249,115,22,0.14),_transparent_30%),linear-gradient(135deg,rgba(255,255,255,0.98),rgba(255,247,237,0.94))]">
          <CardContent className="flex flex-col gap-6 p-5 sm:p-6 xl:flex-row xl:items-end xl:justify-between">
            <div className="space-y-4">
              <div className="inline-flex items-center gap-2 rounded-full border border-orange-200 bg-white/85 px-3 py-1 text-xs font-medium text-orange-800 shadow-sm">
                <AppWindow className="h-3.5 w-3.5" />
                Catálogo conectado ao app
              </div>
              <div className="space-y-2">
                <h1 className="text-3xl font-bold tracking-tight text-foreground">Catálogo do Aplicativo</h1>
                <p className="max-w-4xl text-sm leading-6 text-muted-foreground sm:text-base">
                  Cadastre e organize os serviços que devem aparecer no aplicativo. Tudo o que for salvo em
                  <code className="mx-1 rounded bg-white/80 px-1.5 py-0.5 text-xs">service_categories</code>
                  fica disponível automaticamente para clientes e prestadores que já consomem essa coleção no Firebase.
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Badge variant="outline" className="bg-white/70">{services.length} serviços cadastrados</Badge>
                <Badge variant="outline" className="bg-white/70">{activeCount} ativos no app</Badge>
                <Badge variant="outline" className="bg-white/70">Sync em tempo real</Badge>
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-3 xl:min-w-[520px]">
              <Card className="border-emerald-200 bg-emerald-50/80 shadow-sm">
                <CardContent className="p-4">
                  <p className="text-xs font-medium uppercase tracking-wide text-emerald-700">Ativos</p>
                  <p className="mt-2 text-3xl font-bold text-emerald-600">{activeCount}</p>
                  <p className="mt-1 text-sm text-emerald-700">visíveis no aplicativo</p>
                </CardContent>
              </Card>
              <Card className="border-blue-200 bg-blue-50/80 shadow-sm">
                <CardContent className="p-4">
                  <p className="text-xs font-medium uppercase tracking-wide text-blue-700">Ordem</p>
                  <p className="mt-2 text-3xl font-bold text-blue-600">{services.length > 0 ? services[0].displayOrder : 0}</p>
                  <p className="mt-1 text-sm text-blue-700">primeiro slot atual</p>
                </CardContent>
              </Card>
              <Card className="border-orange-200 bg-orange-50/80 shadow-sm">
                <CardContent className="p-4">
                  <p className="text-xs font-medium uppercase tracking-wide text-orange-700">Coleção</p>
                  <p className="mt-2 text-lg font-bold text-orange-600">service_categories</p>
                  <p className="mt-1 text-sm text-orange-700">fonte do catálogo</p>
                </CardContent>
              </Card>
            </div>
          </CardContent>
        </Card>

        <div className="grid gap-6 2xl:grid-cols-[420px_minmax(0,1fr)]">
          <Card className="border-border/70 shadow-sm">
            <CardHeader className="border-b">
              <CardTitle className="flex items-center gap-2 text-lg">
                {editingId ? <Pencil className="h-5 w-5" /> : <Plus className="h-5 w-5" />}
                {editingId ? "Editar serviço do app" : "Novo serviço para o app"}
              </CardTitle>
              <CardDescription>
                Salve aqui e o catálogo já fica pronto para leitura no aplicativo via Firebase.
              </CardDescription>
            </CardHeader>
            <CardContent className="pt-4">
              <form onSubmit={handleSubmit} className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="name">Nome do serviço</Label>
                  <Input
                    id="name"
                    value={form.name}
                    onChange={(event) => {
                      const value = event.target.value
                      setForm((current) => ({
                        ...current,
                        name: value,
                        slug: current.slug ? current.slug : normalizeSlug(value),
                      }))
                    }}
                    placeholder="Ex.: Troca de bateria"
                    required
                  />
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label htmlFor="slug">Slug técnico</Label>
                    <Input
                      id="slug"
                      value={form.slug}
                      onChange={(event) => setForm((current) => ({ ...current, slug: normalizeSlug(event.target.value) }))}
                      placeholder="troca-de-bateria"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="displayOrder">Ordem de exibição</Label>
                    <Input
                      id="displayOrder"
                      type="number"
                      value={form.displayOrder}
                      onChange={(event) => setForm((current) => ({ ...current, displayOrder: event.target.value }))}
                    />
                  </div>
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label htmlFor="icon">Ícone</Label>
                    <Input
                      id="icon"
                      value={form.icon}
                      onChange={(event) => setForm((current) => ({ ...current, icon: event.target.value }))}
                      placeholder="wrench"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="aliases">Palavras-chave</Label>
                    <Input
                      id="aliases"
                      value={form.aliases}
                      onChange={(event) => setForm((current) => ({ ...current, aliases: event.target.value }))}
                      placeholder="bateria, carga, partida"
                    />
                  </div>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="description">Descrição</Label>
                  <Textarea
                    id="description"
                    rows={4}
                    value={form.description}
                    onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
                    placeholder="Descreva como esse serviço deve aparecer no aplicativo."
                  />
                </div>

                <div className="flex items-center justify-between rounded-xl border bg-muted/20 px-4 py-3">
                  <div>
                    <p className="text-sm font-medium text-foreground">Disponível no aplicativo</p>
                    <p className="text-xs text-muted-foreground">Desative para ocultar do app sem apagar do catálogo.</p>
                  </div>
                  <Switch
                    checked={form.active}
                    onCheckedChange={(checked) => setForm((current) => ({ ...current, active: checked }))}
                  />
                </div>

                <div className="flex flex-wrap gap-2">
                  <Button type="submit" disabled={saving} className="gap-2">
                    {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <ShieldCheck className="h-4 w-4" />}
                    {editingId ? "Salvar alterações" : "Cadastrar serviço"}
                  </Button>
                  <Button type="button" variant="outline" onClick={resetForm} disabled={saving}>
                    Limpar
                  </Button>
                </div>
              </form>
            </CardContent>
          </Card>

          <Card className="border-border/70 shadow-sm">
            <CardHeader className="border-b">
              <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
                <div>
                  <CardTitle className="text-lg">Serviços publicados no app</CardTitle>
                  <CardDescription>
                    O administrador cadastra aqui e a coleção já fica pronta para o aplicativo ler em tempo real.
                  </CardDescription>
                </div>
                <div className="relative w-full lg:w-80">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                    placeholder="Buscar por nome, slug ou palavra-chave"
                    className="pl-10"
                  />
                </div>
              </div>
            </CardHeader>
            <CardContent className="pt-4">
              {loading ? (
                <div className="flex h-52 items-center justify-center text-muted-foreground">
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Carregando catálogo...
                </div>
              ) : filteredServices.length === 0 ? (
                <div className="flex h-52 flex-col items-center justify-center text-center">
                  <Wrench className="mb-3 h-10 w-10 text-muted-foreground/35" />
                  <p className="text-sm font-medium text-foreground">Nenhum serviço encontrado</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Cadastre um serviço à esquerda ou ajuste a busca.
                  </p>
                </div>
              ) : (
                <div className="space-y-3">
                  {filteredServices.map((service) => (
                    <div key={service.id} className="rounded-2xl border bg-card p-4 shadow-sm transition-shadow hover:shadow-md">
                      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                        <div className="min-w-0 space-y-3">
                          <div className="flex flex-wrap items-center gap-2">
                            <h3 className="text-base font-semibold text-foreground">{service.name}</h3>
                            <Badge variant={service.active ? "default" : "outline"}>
                              {service.active ? (
                                <>
                                  <CheckCircle2 className="mr-1 h-3.5 w-3.5" />
                                  Ativo
                                </>
                              ) : (
                                <>
                                  <XCircle className="mr-1 h-3.5 w-3.5" />
                                  Inativo
                                </>
                              )}
                            </Badge>
                            <Badge variant="outline">{service.slug}</Badge>
                          </div>

                          <div className="flex flex-wrap gap-2 text-xs text-muted-foreground">
                            <span className="inline-flex items-center gap-1 rounded-full border px-2 py-1">
                              <ArrowUpDown className="h-3 w-3" />
                              Ordem {service.displayOrder}
                            </span>
                            <span className="inline-flex items-center gap-1 rounded-full border px-2 py-1">
                              Ícone: {service.icon}
                            </span>
                          </div>

                          <p className="text-sm leading-6 text-muted-foreground">
                            {service.description || "Sem descrição cadastrada."}
                          </p>

                          {service.aliases.length > 0 ? (
                            <div className="flex flex-wrap gap-2">
                              {service.aliases.map((alias) => (
                                <Badge key={alias} variant="secondary">{alias}</Badge>
                              ))}
                            </div>
                          ) : null}
                        </div>

                        <div className="flex flex-wrap gap-2 lg:w-[260px] lg:justify-end">
                          <Button
                            type="button"
                            variant="outline"
                            onClick={() => fillForm(service)}
                            className="gap-2"
                          >
                            <Pencil className="h-4 w-4" />
                            Editar
                          </Button>
                          <Button
                            type="button"
                            variant={service.active ? "secondary" : "default"}
                            onClick={() => handleToggleActive(service)}
                          >
                            {service.active ? "Desativar" : "Ativar"}
                          </Button>
                          <Button
                            type="button"
                            variant="outline"
                            onClick={() => handleDelete(service)}
                            className="gap-2 text-red-600 hover:text-red-700"
                          >
                            <Trash2 className="h-4 w-4" />
                            Excluir
                          </Button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </PageWithBack>
  )
}
