window.__ModuleLoader__.load({
  id: '@dsh-mobile/client-ui',
  factory() {
    const STYLE_ID = 'dsh-mobile-client-ui-style'
    const CSS = `
      html[data-dsh-mobile-ui="1"] {
        color-scheme: light dark;
      }
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
      /* The fixed sidebar and hidden rightbar leave the center column to
         auto-placement, where it lands in the 0px first track. Pin it to the
         real content track so it has a non-zero hit-test box. */
      html[data-dsh-mobile-ui="1"] .mP3x-q_centerCol {
        grid-column: 2 !important;
        grid-row: 1 !important;
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
      /* Keep a close affordance at the drawer edge so ≡ toggles both ways. */
      html[data-dsh-mobile-drawer-open="1"] #dsh-mobile-drawer-handle {
        display: flex;
        left: min(82vw, 320px);
        top: 42%;
        width: 34px;
        height: 40px;
        border-radius: 0 12px 12px 0;
        font-size: 20px;
        z-index: 56;
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
      /* DSH keeps the collapsed column in the grid for the desktop rail. On
         phones hide it only while no fullscreen panel is open; the Files and
         Terminal panels deliberately use data-rightbar-collapsed with an
         open overlay. Hiding on the collapsed attribute alone made the panels
         open invisibly. */
      html[data-dsh-mobile-ui="1"] .mP3x-q_frame[data-rightbar-collapsed] .mP3x-q_rightbarCol:not(:has([data-sidebar-right-open="true"])) {
        display: none !important;
      }
      html[data-dsh-mobile-ui="1"] .mP3x-q_frame[data-rightbar-collapsed] .mP3x-q_rightbarCol:has([data-sidebar-right-open="true"]) {
        display: block !important;
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
        if (document.head) {
          document.head.appendChild(style)
        } else {
          document.addEventListener(
            'DOMContentLoaded',
            () => document.head?.appendChild(style),
            { once: true }
          )
        }

        // Notification deep-link bridge: Android calls this after a tap and
        // lets DSH's own workspace service perform the navigation. The service
        // may arrive after this plugin, so wait for it instead of sampling once.
        let openSession = () => false
        window.__DSH_MOBILE_OPEN_SESSION__ = (sessionId) => openSession(sessionId)
        ctx.inject(['uiWorkspace'], (scope) => {
          const workspace = scope.uiWorkspace
          openSession = (sessionId) => {
            try {
              if (!workspace || typeof workspace.openSession !== 'function') return false
              workspace.openSession(sessionId)
              return true
            } catch (error) {
              return false
            }
          }
          return () => {
            openSession = () => false
          }
        })

        // Files/Terminal use DSH's right-sidebar service directly. DOM clicking
        // is brittle because the collapsed panel is display:none on phones.
        let openPanel = () => false
        let closePanel = () => false
        window.__DSH_MOBILE_OPEN_PANEL__ = (kind) => openPanel(kind)
        window.__DSH_MOBILE_CLOSE_PANEL__ = () => closePanel()
        ctx.inject(['sidebarRight'], (scope) => {
          const service = scope.sidebarRight
          openPanel = (kind) => {
            try {
              if (!service || typeof service.openTab !== 'function') return false
              service.openTab(kind)
              return true
            } catch (error) {
              return false
            }
          }
          closePanel = () => {
            try {
              const sessionId = service?.mounted?.getSnapshot?.()
              if (sessionId === undefined || typeof service.isExpanded !== 'function') {
                return false
              }
              if (service.isExpanded(sessionId)) service.toggleExpanded(sessionId)
              return true
            } catch (error) {
              return false
            }
          }
          return () => {
            openPanel = () => false
            closePanel = () => false
          }
        })
        // DownloadManager cannot resolve blob: URLs. DSH generates PDFs and
        // session archives in the page and clicks a download anchor; stream
        // those bytes to the Android bridge in bounded chunks instead.
        function bytesToBase64(bytes) {
          let binary = ''
          const step = 0x8000
          for (let offset = 0; offset < bytes.length; offset += step) {
            binary += String.fromCharCode.apply(
              null,
              bytes.subarray(offset, Math.min(offset + step, bytes.length))
            )
          }
          return btoa(binary)
        }

        async function saveBlob(url, name) {
          const bridge = window.AndroidDownloader
          if (!bridge || typeof bridge.begin !== 'function') return false
          const response = await fetch(url)
          const blob = await response.blob()
          const id = bridge.begin(name || 'download', blob.type || 'application/octet-stream')
          if (!id) return false
          try {
            const chunkSize = 256 * 1024
            for (let offset = 0; offset < blob.size; offset += chunkSize) {
              const chunk = new Uint8Array(
                await blob.slice(offset, offset + chunkSize).arrayBuffer()
              )
              if (!bridge.writeChunk(id, bytesToBase64(chunk))) {
                bridge.abort(id)
                return false
              }
            }
            return bridge.finish(id) === true
          } catch (error) {
            bridge.abort(id)
            return false
          }
        }

        window.__DSH_MOBILE_SAVE_BLOB__ = (url, name) => {
          if (!window.AndroidDownloader) return false
          void saveBlob(url, name)
          return true
        }

        const onBlobDownloadClick = (event) => {
          if (!window.AndroidDownloader) return
          const path = typeof event.composedPath === 'function' ? event.composedPath() : []
          const anchor = path.find(
            (element) => element instanceof HTMLAnchorElement &&
              typeof element.href === 'string' &&
              element.href.startsWith('blob:')
          )
          if (!anchor) return
          event.preventDefault()
          event.stopImmediatePropagation()
          void saveBlob(anchor.href, anchor.download || 'download')
        }
        document.addEventListener('click', onBlobDownloadClick, true)

        const query = window.matchMedia(
          '(max-width: 820px), (max-height: 500px) and (pointer: coarse)'
        )
        const applyMarker = () => {
          if (query.matches) {
            document.documentElement.dataset.dshMobileUi = '1'
          } else {
            delete document.documentElement.dataset.dshMobileUi
            closeDrawer(false)
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

        // The rail is DSH's collapsed sidebar: icons only. Expand it while the
        // mobile drawer is open so every icon gets its label and the session
        // list fills what was otherwise a large blank panel.
        function sidebarRoot() {
          return document.querySelector('.mP3x-q_sidebarCol [class*="wtg9ga_root"]')
        }
        function setSidebarExpanded(expanded) {
          const root = sidebarRoot()
          if (!root) return
          const collapsed = root.classList.contains('wtg9ga_collapsed')
          if (expanded === !collapsed) return
          const toggle = root.querySelector('button.wtg9ga_toggle')
          if (toggle) toggle.click()
        }

        let expandTimer = null
        function closeDrawer(restoreSidebar = true) {
          if (expandTimer !== null) {
            clearTimeout(expandTimer)
            expandTimer = null
          }
          document.documentElement.removeAttribute('data-dsh-mobile-drawer-open')
          handle.textContent = '☰'
          handle.setAttribute('aria-label', '打开菜单')
          if (restoreSidebar) setSidebarExpanded(false)
        }
        function openDrawer() {
          document.documentElement.setAttribute('data-dsh-mobile-drawer-open', '1')
          handle.textContent = '×'
          handle.setAttribute('aria-label', '关闭菜单')
          setSidebarExpanded(true)
          // DSH may restore its persisted collapsed rail while the page is
          // still settling; expand again after the transition.
          if (expandTimer !== null) clearTimeout(expandTimer)
          expandTimer = setTimeout(() => {
            expandTimer = null
            if (document.documentElement.hasAttribute('data-dsh-mobile-drawer-open')) {
              setSidebarExpanded(true)
            }
          }, 400)
        }
        function toggleDrawer() {
          if (document.documentElement.hasAttribute('data-dsh-mobile-drawer-open')) {
            closeDrawer()
          } else {
            openDrawer()
          }
        }

        const onHandleClick = (event) => {
          event.preventDefault()
          toggleDrawer()
        }
        const onBackdropClick = () => closeDrawer()

        if (document.body) {
          document.body.appendChild(handle)
          document.body.appendChild(backdrop)
          handle.addEventListener('click', onHandleClick)
          backdrop.addEventListener('click', onBackdropClick)
        }

        applyMarker()
        query.addEventListener('change', applyMarker)

        if (typeof ctx?.effect === 'function') {
          ctx.effect(() => () => {
            try { query.removeEventListener('change', applyMarker) } catch {}
            try { document.removeEventListener('click', onBlobDownloadClick, true) } catch {}
            try { delete window.__DSH_MOBILE_SAVE_BLOB__ } catch {}
            try { handle.removeEventListener('click', onHandleClick) } catch {}
            try { backdrop.removeEventListener('click', onBackdropClick) } catch {}
            try { if (expandTimer !== null) clearTimeout(expandTimer) } catch {}
            try { handle.remove() } catch {}
            try { backdrop.remove() } catch {}
            try { closeDrawer() } catch {}
            try { delete window.__DSH_MOBILE_OPEN_SESSION__ } catch {}
            try { delete window.__DSH_MOBILE_OPEN_PANEL__ } catch {}
            try { delete window.__DSH_MOBILE_CLOSE_PANEL__ } catch {}
            try { style.remove() } catch {}
            try { delete document.documentElement.dataset.dshMobileUi } catch {}
          })
        }
      },
    }
  },
})
