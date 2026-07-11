# NetHAL — Design

Design system e protótipos do NetHAL Lab, mantidos pela Vera com **Claude Design** (Artifacts,
nunca Figma). Origem: `Claude Design/Nethal/Nethal Network Hardware Platform` na máquina local do
Luiz — esta pasta é a cópia versionada, fonte da verdade para o time.

## Conteúdo

- **`design-system.dc.html`** — tokens de cor (dark/light), tipografia, espaçamento/raio/elevação,
  iconografia, componentes (navbar, topbar, botões, sheets, diálogos, notificações), motion e
  contraste/acessibilidade. Abrir no navegador para visualizar.
- **`prototypes.dc.html`** — 36 telas navegáveis: onboarding, pareamento do roteador, uso diário
  (Status/Wi-Fi/Configurações/Dispositivos, dark e light) e ferramentas avançadas.
- **`assets/brand/`** — logo (`logo-icon.svg`), favicon e lockups dark/light.
- **`assets/icons/dark/` e `assets/icons/light/`** — ícones outline (mesmo nome nos dois temas):
  actions, camera, channel, chevron-right, close, edit, firmware, iot-sensor, megaphone, overview,
  password, reboot, router, switch, warning, wifi.
- **`support.js` / `image-slot.js` / `android-frame.jsx`** — runtime do canvas Claude Design, exigido
  pelos `.dc.html` acima (não editar à mão).
- **`specs/`** — specs textuais complementares ao `.dc.html`, escritas quando uma tela do
  protótipo diverge da implementação real (catálogo de drivers, permissões Android, etc.) e
  precisa de decisão registrada além do visual. Em caso de conflito entre uma spec em `specs/`
  e o `.dc.html`, a spec vence para as telas que ela cobre — ela existe justamente para corrigir
  o protótipo.

## Como consultar

Resumo condensado e consultável via skill **`/nethal-design`** — usar antes de desenhar ou
implementar qualquer tela/componente do Lab. Em caso de divergência, o `design-system.dc.html`
deste diretório é quem vence (a skill deve ser atualizada para acompanhar).

## Histórico

`_archive/2026-07-11-design-v1/` guarda a exploração de marca anterior (fonte Space Grotesk,
paleta e diretriz "evitar cyberpunk"), superada pelo design system atual ("dark cyber utilitário",
fonte única Google Sans Flex). Mantido por histórico, não é referência ativa.
