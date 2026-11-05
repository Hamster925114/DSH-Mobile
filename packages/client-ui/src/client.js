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
      html:not([data-dsh-mobile-ui="1"]) #dsh-mobile-drawer-handle,
      html:not([data-dsh-mobile-ui="1"]) #dsh-mobile-drawer-backdrop {
        display: none !important;
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

      /* Mobile drawer: hide the 56px rail track; the rail itself becomes an
         overlay opened from the left edge. */
      html[data-dsh-mobile-ui="1"] .mP3x-q_frame {
        grid-template-columns: 0 minmax(0, 1fr) 0 !important;
      }
      html[data-dsh-mobile-ui="1"] .mP3x-q_sidebarCol {
        position: fixed !important;
        top: 0;
        bottom: 0;
        left: 0;
        width: min(82vw, 320px) !important;
        z-index: 52;
        transform: translateX(-102%);
        transition: transform 0.22s var(--ds-ease-in-out, ease);
        box-shadow: 18px 0 40px rgba(0, 0, 0, 0.35);
        background: var(--dsw-specific-sidebar-fill, var(--dsw-alias-bg-layer-2));
      }
      html[data-dsh-mobile-drawer-open="1"] .mP3x-q_sidebarCol {
        transform: translateX(0);
      }
      #dsh-mobile-drawer-handle {
        position: fixed;
        left: 0;
        top: 42%;
        z-index: 55;
        width: 22px;
        height: 64px;
        padding: 0;
        border: 1px solid var(--dsw-alias-border-l2, rgba(255,255,255,.16));
        border-left: 0;
        border-radius: 0 12px 12px 0;
        background: var(--dsw-specific-input-major, var(--dsw-alias-bg-layer-2));
        color: var(--dsw-alias-label-secondary, #c9d3e6);
        font-size: 18px;
        line-height: 1;
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: 6px 0 18px rgba(0, 0, 0, 0.25);
      }
      html[data-dsh-mobile-drawer-open="1"] #dsh-mobile-drawer-handle {
        display: none;
      }
      #dsh-mobile-drawer-backdrop {
        display: none;
        position: fixed;
        inset: 0;
        z-index: 51;
        background: rgba(0, 0, 0, 0.42);
      }
      html[data-dsh-mobile-drawer-open="1"] #dsh-mobile-drawer-backdrop {
        display: block;
      }

      /* File/Terminal right sidebar: full-screen sheet on phones. */
      html[data-dsh-mobile-ui="1"] .mP3x-q_frame:not([data-rightbar-collapsed]) .mP3x-q_rightbarCol {
        position: fixed !important;
        inset: 0 !important;
        width: 100vw !important;
        max-width: 100vw !important;
        z-index: 45;
        background: var(--dsw-alias-bg-base, #0d1117);
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
            closeDrawer()
          }
        }

        const oldHandle = document.getElementById('dsh-mobile-drawer-handle')
        const oldBackdrop = document.getElementById('dsh-mobile-drawer-backdrop')
        oldHandle?.remove()
        oldBackdrop?.remove()

        const handle = document.createElement('button')
        handle.id = 'dsh-mobile-drawer-handle'
        handle.type = 'button'
        handle.setAttribute('aria-label', '打开菜单')
        handle.textContent = '☰'

        const backdrop = document.createElement('div')
        backdrop.id = 'dsh-mobile-drawer-backdrop'

        function closeDrawer() {
          document.documentElement.removeAttribute('data-dsh-mobile-drawer-open')
        }
        function toggleDrawer() {
          if (document.documentElement.hasAttribute('data-dsh-mobile-drawer-open')) {
            closeDrawer()
          } else {
            document.documentElement.setAttribute('data-dsh-mobile-drawer-open', '1')
          }
        }

        const onHandleClick = (event) => {
          event.preventDefault()
          toggleDrawer()
        }
        const onBackdropClick = () => closeDrawer()
        const onDocumentClick = (event) => {
          if (!document.documentElement.hasAttribute('data-dsh-mobile-drawer-open')) return
          const target = event.target
          if (!(target instanceof Element)) return
          if (target.closest('#dsh-mobile-drawer-backdrop')) {
            closeDrawer()
            return
          }
          const sidebar = target.closest('.mP3x-q_sidebarCol')
          if (sidebar && target.closest('button')) {
            setTimeout(closeDrawer, 180)
          }
        }

        if (document.body) {
          document.body.appendChild(handle)
          document.body.appendChild(backdrop)
          handle.addEventListener('click', onHandleClick)
          backdrop.addEventListener('click', onBackdropClick)
          document.addEventListener('click', onDocumentClick, true)
        }

        applyMarker()
        query.addEventListener('change', applyMarker)

        if (typeof ctx?.effect === 'function') {
          ctx.effect(() => () => {
            try { query.removeEventListener('change', applyMarker) } catch {}
            try { handle.removeEventListener('click', onHandleClick) } catch {}
            try { backdrop.removeEventListener('click', onBackdropClick) } catch {}
            try { document.removeEventListener('click', onDocumentClick, true) } catch {}
            try { handle.remove() } catch {}
            try { backdrop.remove() } catch {}
            try { closeDrawer() } catch {}
            try { style.remove() } catch {}
            try { delete document.documentElement.dataset.dshMobileUi } catch {}
          })
        }
      },
    }
  },
})
