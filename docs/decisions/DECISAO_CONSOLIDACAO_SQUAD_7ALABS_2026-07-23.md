# Decisão — Consolidação de squad da 7ALabs (2026-07-23)

- **Status:** ativo
- **Escopo:** squad do NetHAL (Rafael, Caio, Vera, Marisa — todos aposentados)
- **Documento canônico:** `C:\Projetos\docs\decisions\DECISAO_CONSOLIDACAO_SQUAD_7ALABS_2026-07-23.md`
  (raiz do workspace) — este arquivo é um espelho local com o recorte específico do Nethal.

## O que mudou para este repo

O squad próprio do NetHAL (Rafael, Caio, Vera, Marisa) foi **aposentado por completo** — o repo
não tem mais agentes próprios em `.claude/agents/`. As personas foram arquivadas em
`.claude/agents/_archive/*_2026-07-23_consolidado.md` (histórico, não invocar mais).

O NetHAL passa a ser atendido pelo mesmo quadro de agentes do SignallQ, agora de nível de usuário
(`~/.claude/agents/`):

| Papel no NetHAL (antigo) | Agente global (novo) |
|---|---|
| Rafael (PO, promoção de driver) | Claudete |
| Caio (SDK, app, drivers) | Camilo |
| Vera (UX/design do Lab) | Lia |
| Marisa (QA/segurança/release) | Rhodolfo |

Motivo: o NetHAL e o SignallQ tinham squads funcionalmente idênticos com nomes diferentes. O Luiz
pediu pra tratar a 7ALabs como uma empresa só — ver decisão canônica para o raciocínio completo.

## O que não mudou

- Todas as convenções deste repo continuam valendo: GitHub Issues (`gmmattey/nethal`), ciclo de
  vida de driver, os três não-negociáveis de segurança, skills locais (`/modelo-capacidades`,
  `/seguranca-nethal`, `/ciclo-vida-driver`, `/nethal-design` etc.).
- A regra de que promoção de estágio de driver é decisão de produto (agora da Claudete) com
  segurança como parte da revisão normal (agora do Rhodolfo) — sem sign-off bloqueante — continua
  igual.
- Marca própria do NetHAL, distinta do SignallQ, continua sendo respeitada pela Lia ao desenhar.

## Ponto de atenção já identificado

Rafael descrevia um mecanismo de fila em arquivo (`.claude/tasks/queue/<agente>/`) para controle
de WIP que nunca foi confirmado como existente de fato no repo (mesmo padrão do SignallQ, onde
auditoria de 2026-07-21 confirmou que o controle real é por dispatch via `Agent`/`SendMessage`, sem
diretório de fila). A Claudete (global) trata esse mecanismo como aspiracional até alguém confirmar
o contrário.
