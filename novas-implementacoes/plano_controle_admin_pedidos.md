# Plano de Implementação: Painel de Controle de Pedidos em Andamento (Admin)

## 1. Visão Geral
O objetivo deste plano é criar uma aba específica no painel do administrador focada no monitoramento em tempo real dos pedidos em andamento. A funcionalidade visa dar controle total ao administrador sobre o status dos serviços, localização dos prestadores e identificação proativa de problemas (ex: prestador ocioso após aceitar o pedido).

## 2. Requisitos da Interface (Aba "Monitoramento de Pedidos")
- **Dashboard Centralizado:** Uma nova aba no painel admin onde todos os pedidos com status "Em Andamento" ou "Aceito" são listados.
- **Informações do Pedido:** 
  - Código único do pedido.
  - Nome do cliente e do prestador alocado.
  - Horário de aceitação do pedido.
- **Geolocalização em Tempo Real:** 
  - Exibição de um mapa (opcional) ou status em texto de onde o prestador está no momento.
  - Distância estimada ou tempo até o cliente.

## 3. Sistema de Alertas e Regras de Negócio
- **Detecção de Ociosidade (Prestador não se deslocou):**
  - Implementar um rastreamento contínuo (via GPS do app do prestador) que compara a localização do aceite com a localização atual a cada *X* minutos.
  - Se o prestador aceitar o pedido e sua localização não mudar significativamente em *Y* minutos, o sistema deve disparar um alerta visual e sonoro no painel do admin.
- **Notificações para o Admin:**
  - "Aviso: O prestador [Nome] do pedido [Código] aceitou o serviço há 10 minutos mas não iniciou o deslocamento."

## 4. Ações Disponíveis para o Admin
- **Reatribuição de Pedido:** Botão rápido para remover o prestador atual (com ou sem penalidade/aviso automático) e disparar o pedido novamente para a fila ou atribuir manualmente a outro prestador próximo.
- **Contato Rápido:** Botões para iniciar chat/ligação direta com o cliente ou com o prestador para entender o atraso.
- **Cancelamento Administrativo:** Opção de cancelar o pedido com justificativa.

## 5. Fluxo de Implementação Sugerido
1. **Banco de Dados:** Adicionar campos para logs de localização do prestador e timestamps precisos de mudança de status (aceite, em deslocamento, no local).
2. **Backend:** 
   - Criar rotas para receber atualizações de localização do app do prestador (background tracking).
   - Implementar um cron job ou worker para verificar continuamente as regras de ociosidade e criar notificações (eventos) para o painel admin.
3. **Frontend (Admin):** 
   - Criar a nova aba "Monitoramento de Pedidos".
   - Integrar WebSockets ou polling para atualizar os dados e alertas em tempo real.
4. **Aplicativo do Prestador:** 
   - Garantir que a permissão de localização em background esteja ativa enquanto o pedido estiver "em andamento".

## 6. Próximos Passos
- Validar as regras de tempo limite de deslocamento (ex: quantos minutos tolerar antes de alertar o admin).
- Escolher a tecnologia de mapas/geolocalização para o dashboard admin.
- Estimar o esforço de desenvolvimento do frontend e backend.
