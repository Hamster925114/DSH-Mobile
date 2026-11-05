window.__ModuleLoader__.load({
  id: '@dsh-mobile/client-ui',
  factory() {
    const STYLE_ID = 'dsh-mobile-client-ui-style'
    const CSS = `
      html[data-dsh-mobile-ui="1"] body {
        overscroll-behavior-y: none;
      }
      html[data-dsh-mobile-ui="1"] * {
        -webkit-tap-highlight-color: transparent;
      }
      html[data-dsh-mobile-ui="1"] button,
      html[data-dsh-mobile-ui="1"] [role="button"] {
        touch-action: manipulation;
      }

      /* Use the phone width for the conversation column instead of the
         desktop 680–920px clamp. */
      html[data-dsh-mobile-ui="1"] .an3WzG_body {
        --dsh-chat-content-width: 100% !important;
        --dsh-composer-card-max-width: 100% !important;
      }
      html[data-dsh-mobile-ui="1"] .an3WzG_header {
        padding-left: 12px !important;
        padding-right: 12px !important;
        min-height: 58px !important;
      }
      html[data-dsh-mobile-ui="1"] .an3WzG_composerSeat {
        padding-left: 4px;
        padding-right: 4px;
      }
      html[data-dsh-mobile-ui="1"] .an3WzG_crumb {
        max-width: 150px;
      }
      html[data-dsh-mobile-ui="1"] .an3WzG_tabs {
        gap: 14px 16px;
        padding-left: 4px;
      }

      /* The desktop turn rail eats precious horizontal space on a phone. */
      html[data-dsh-mobile-ui="1"] .SNx2jG_frame {
        display: none !important;
      }

      /* Larger menu rows and close targets. */
      html[data-dsh-mobile-ui="1"] [role="menu"] [role="menuitem"] {
        min-height: 40px;
      }
      html[data-dsh-mobile-ui="1"] [role="menu"] {
        max-width: calc(100vw - 16px) !important;
      }

      /* Keep full-screen side panels usable above the native bottom bar. */
      html[data-dsh-mobile-ui="1"] .mP3x-q_rightbarCol {
        max-width: 100vw;
      }

      @media (max-width: 820px) {
        html[data-dsh-mobile-ui="1"] .an3WzG_titleRow {
          padding-inline-start: 4px !important;
        }
        html[data-dsh-mobile-ui="1"] .an3WzG_headerUtilities {
          margin-left: 8px !important;
        }
      }
    `

    return {
      inject: [],
      apply(ctx) {
        if (typeof document === 'undefined') return

        const existing = document.getElementById(STYLE_ID)
        if (existing) existing.remove()
        const style = document.createElement('style')
        style.id = STYLE_ID
        style.dataset.plugin = '@dsh-mobile/client-ui'
        style.textContent = CSS
        document.head.appendChild(style)

        const query = window.matchMedia('(max-width: 820px)')
        const applyMarker = () => {
          if (query.matches) {
            document.documentElement.dataset.dshMobileUi = '1'
          } else {
            delete document.documentElement.dataset.dshMobileUi
          }
        }
        applyMarker()
        query.addEventListener('change', applyMarker)

        if (typeof ctx?.effect === 'function') {
          ctx.effect(() => () => {
            try { query.removeEventListener('change', applyMarker) } catch {}
            try { style.remove() } catch {}
            try { delete document.documentElement.dataset.dshMobileUi } catch {}
          })
        }
      },
    }
  },
})
