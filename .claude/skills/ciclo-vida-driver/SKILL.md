---
name: ciclo-vida-driver
description: Estágios de um driver (DRAFT a STABLE), critérios objetivos de promoção e critérios para um driver entrar no SignallQ. Consultar antes de propor promoção de estágio ou declarar um driver pronto para uso além de laboratório.
---

Consulte os critérios de estágio relevantes para a tarefa abaixo:

$ARGUMENTS

Fonte completa: `docs/product/specification.md` §9, §16, `CONTRIBUTING.md`.

---

## Estágios

```text
DRAFT
DISCOVERY_ONLY
READ_ONLY_ALPHA
READ_ONLY_BETA
WRITE_BETA
STABLE
DEPRECATED
BLOCKED
```

Progressão é sempre sequencial. Nenhum driver pula de `DISCOVERY_ONLY` direto para `WRITE_BETA`.

## Critério para `STABLE`

- Pelo menos 20 testes bem-sucedidos.
- Cobertura de pelo menos 3 firmwares diferentes, ou firmware único com justificativa documentada.
- Taxa de falha crítica abaixo de 2%.
- Nenhuma ação registrada que derrube conectividade sem aviso.
- Documentação de capabilities completa.
- Fallback seguro implementado.
- Logs suficientes para diagnóstico.

## Gate obrigatório em cada transição

- `DRAFT → DISCOVERY_ONLY`: driver identificado, sem ainda ler dado real do equipamento.
- `DISCOVERY_ONLY → READ_ONLY_ALPHA`: pelo menos um teste real (modelo + firmware) documentado por Diego.
- `READ_ONLY_ALPHA → READ_ONLY_BETA`: capabilities de leitura declaradas e revisadas por Marisa quanto a telemetria.
- `READ_ONLY_BETA → WRITE_BETA`: **exige sign-off explícito de Marisa** — toda capability de escrita passou pelo Safety Guard (`/seguranca-nethal`).
- `WRITE_BETA → STABLE`: critérios objetivos acima cumpridos + aprovação de Rafael.
- Qualquer estágio `→ BLOCKED`: Marisa pode bloquear a qualquer momento se detectar risco de segurança, independente do estágio atual.

## Critérios para um driver entrar no SignallQ

Um driver NetHAL só é proposto para o SignallQ quando:

- Está marcado `STABLE`.
- Tem documentação de limitações.
- Não depende de fluxo frágil demais.
- Foi testado em massa real (não só em laboratório interno).
- Não exige permissões abusivas.
- Tem fallback quando falha.
- Não prejudica a experiência principal do SignallQ.
- Não promete controle universal.

A decisão final de propor um driver para o SignallQ é do Rafael, após sign-off de segurança da Marisa.

## Limites

- Esta skill define o gate, não o implementa — quem evidencia teste é Diego, quem aprova segurança é Marisa, quem decide promoção final é Rafael.
- Estágio declarado sem evidência documentada (teste real, revisão) não vale — sempre exigir rastro.
