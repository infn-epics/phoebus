# Custom Features — INFN-LNF Phoebus Fork

This document describes the features added to this fork of
[ControlSystemStudio/phoebus](https://github.com/ControlSystemStudio/phoebus).
All settings mentioned below go in **`settings.ini`** and are prefixed with
their package path (e.g. `org.phoebus.logbook.olog.ui/pdf_font_size=14`).

---

## Table of Contents

1. [HTML Report Export](#1-html-report-export)
2. [Logbook Template Manager](#2-logbook-template-manager)
3. [OAuth2 / OIDC Authentication for Olog](#3-oauth2--oidc-authentication-for-olog)

---

## 1. HTML Report Export

### Overview

A print-ready report can be generated directly from the **Log Entry Table**
view. Right-click the table and choose **Export HTML Report** from the context
menu.

The report is rendered as A4-formatted HTML and saved to a file. The file is
then opened in the system browser, where you can use **Print → Save as PDF**
to produce a PDF.

### Report Structure

| Page | Content |
|------|---------|
| **Cover** | Configurable title and subtitle, date range of the entries (not the search query), total entry count, generation timestamp. |
| **Table of Contents** *(optional)* | Entries grouped by day, each showing a sequential number, title, time and author. |
| **Log Entries** *(one per page)* | Header with title and accent stripe, metadata grid (date, author, level, logbooks, tags), Markdown body rendered to HTML, embedded attachment images, non-image file list, property tables. |
| **Footer** *(each entry page)* | Report title — "Entry N of M" — timestamp. |

### Attachment Images

When `pdf_embed_images` is `true` (the default), attachment images are
downloaded from the Olog server and saved as **local temp files** alongside
the report so it renders correctly offline. When the report is saved via the
file dialog, the images are copied next to the HTML file with relative paths.

Set the option to `false` if you prefer faster generation with external URL
references (images will only render if the Olog server is reachable when
viewing the report).

### Settings Reference

All keys live under the prefix **`org.phoebus.logbook.olog.ui/`**.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `pdf_font_family` | String | `'Times New Roman', Times, serif` | CSS `font-family` applied to the entire report. |
| `pdf_font_size` | int | `14` | Base body font size in **pt**. All other sizes (titles, metadata, badges, cover heading, footer) scale proportionally from this value. Recommended range: 10–14. |
| `pdf_title` | String | `Olog` | Title displayed on the cover page. |
| `pdf_subtitle` | String | `Electronic Logbook` | Subtitle displayed below the title on the cover page. |
| `pdf_accent_color` | String | `#1a237e` | Hex colour used for entry titles, header stripes, blockquote borders and links. |
| `pdf_embed_images` | boolean | `true` | Download and embed attachment images as base64. |
| `pdf_toc` | boolean | `true` | Include a Table of Contents page after the cover. |

### Font Scaling

All sizes derive from `pdf_font_size` (denoted *B* below):

| Element | Size |
|---------|------|
| Cover title | 2.67 × B |
| Cover subtitle | 1.25 × B |
| Entry title | 1.50 × B |
| Body text | 1.00 × B |
| Metadata / section headings | 0.92 × B |
| Badges / code blocks | 0.83 × B |
| Captions | 0.79 × B |
| Footer | 0.75 × B |

### Example `settings.ini`

```ini
# ---------- HTML Report Export ----------
org.phoebus.logbook.olog.ui/pdf_font_family='Times New Roman', Times, serif
org.phoebus.logbook.olog.ui/pdf_font_size=14
org.phoebus.logbook.olog.ui/pdf_title=ELI-NP Olog
org.phoebus.logbook.olog.ui/pdf_subtitle=Electronic Logbook — Daily Report
org.phoebus.logbook.olog.ui/pdf_accent_color=#1a237e
org.phoebus.logbook.olog.ui/pdf_embed_images=true
org.phoebus.logbook.olog.ui/pdf_toc=true
```

---

## 2. Logbook Template Manager

### Overview

A full **CRUD admin application** for managing Olog server resources
(templates, logbooks, tags, and properties). Open it from the Phoebus menu:
**Utility → Logbook Template Manager**.

### Tabs

#### Templates

| Column | Description |
|--------|-------------|
| Name | Template name (unique, case-insensitive). |
| Title | Default title pre-filled when the template is used. |
| Level | Default severity level. |
| Owner | User who created the template. |

- **New** — Opens a dialog to compose a new template with title, body
  (Commonmark), level, logbook/tag/property selection. Properties can carry
  pre-filled attribute values.
- **Edit** — Opens the same dialog pre-populated with the selected template.
- **Delete** — Removes the selected template from the server.
- A live **Markdown preview** pane renders the template body in a WebView.

#### Logbooks

- Table with **Name** and **Owner** columns.
- Inline fields to type a new logbook name and owner, then press **Create**.
- **Delete** removes the selected logbook.
- A **JSON preview** shows the raw server representation.

#### Tags

- Table with **Name** and **State** columns.
- Inline field for a new tag name, then press **Create**.
- **Delete** removes the selected tag.
- JSON preview panel.

#### Properties

Properties are key-value schemas that can be attached to log entries and
templates. They must exist on the server before a template can reference
them.

- Table with **Name**, **Owner**, and **Attributes** columns.
- Inline fields for name, owner, and a **comma-separated list of attribute
  names** (e.g. `gun, dipole, target`).
- **Create** sends the property with its attribute list to the server.
- **Delete** removes the selected property.
- JSON preview panel.

#### Toolbar

| Button | Action |
|--------|--------|
| **Refresh** | Reloads data from the Olog server for the active tab. |
| **Import** | Imports a JSON file. Supports single-object and array formats. The import target matches the active tab (templates, logbooks, tags, or properties). |
| **Export** | Exports the selected items (or all) of the active tab to a JSON file. |

### Authentication

The Template Manager shares the same authentication mechanism as the rest of
the Olog UI:

- When `oauth2_auth_olog_enabled=true`, it uses **Bearer JWT tokens** from
  the Phoebus `SecureStore`.
- Otherwise, it falls back to **Basic authentication** using the credentials
  stored in the secure store.

---

## 3. OAuth2 / OIDC Authentication for Olog

### Overview

When enabled, the Olog logbook UI and Template Manager authenticate using
**OAuth2 / OpenID Connect JWT tokens** instead of username/password. This
is designed for deployments using an identity provider such as **Keycloak**.

### Behaviour When Enabled

1. The **username and password fields** are hidden in the log entry editor.
2. Login validation no longer checks credentials — only a title and at least
   one logbook selection are required.
3. On submit, a **JWT token** is retrieved from the Phoebus `SecureStore`
   (populated by the Phoebus OIDC login flow) and sent as
   `Authorization: Bearer <token>`.
4. Token validity is verified locally via **JWKS** (the identity provider's
   public key set) before each request.

### Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `org.phoebus.logbook.olog.ui/oauth2_auth_olog_enabled` | boolean | `false` | Enable OAuth2/OIDC JWT authentication. When `true`, the username/password fields are hidden and Bearer tokens are used. |

### Example `settings.ini`

```ini
# Enable OAuth2 for Olog (requires a configured OIDC provider)
org.phoebus.logbook.olog.ui/oauth2_auth_olog_enabled=true
```

### Server Requirements

The Olog server must be configured to accept JWT Bearer tokens. The
companion [phoebus-olog](https://github.com/infn-epics/phoebus-olog) fork
includes:

- A `JwtAuthenticationFilter` that extracts and validates Bearer tokens.
- `WebSecurityConfig` with OAuth2 resource-server support alongside
  traditional Basic auth.
- Explicit `@PathVariable(name = "...")` annotations on all REST endpoints
  to work correctly with Java 25 (which does not retain parameter names via
  reflection by default).

---

## Known Limitations & Deployment Notes

### Attachment Upload Size (HTTP 413)

When submitting a log entry with large images or attachments, the **nginx
ingress controller** may reject the request with:

```
HTTP 413: Request Entity Too Large
```

This is because nginx defaults to a **1 MB** request body limit. Fix it by
annotating the Olog Kubernetes Ingress:

```bash
kubectl -n <namespace> annotate ingress olog \
  nginx.ingress.kubernetes.io/proxy-body-size=50m
```

Or in Helm values:

```yaml
olog:
  ingress:
    annotations:
      nginx.ingress.kubernetes.io/proxy-body-size: "50m"
```

### HTML Rendering Notes

The HTML export generates a self-contained report that is opened in the
system browser (e.g. Firefox, Chrome). Some notes:

- Use the browser's **Print → Save as PDF** to produce a PDF file.
- **CSS `@page` rules** control page breaks and margins — rendering may
  vary slightly across browsers.
- **Very large reports** (hundreds of entries with many images) may take a
  moment to load. Consider disabling `pdf_embed_images` or reducing the
  number of entries in the search.
- The **Table of Contents** is a visual reference only (no clickable
  hyperlinks within the printed PDF).
- **Font availability**: the configured `pdf_font_family` must be installed
  on the machine running the browser. If not found, the browser falls back
  to a default serif/sans-serif font.

### Java 25 and `@PathVariable`

The phoebus-olog server is compiled with Java 25, which no longer retains
method parameter names via reflection by default. All `@PathVariable`
annotations in the REST controllers must use explicit `name = "..."` to
avoid HTTP 500 errors. This has been fixed in the INFN fork.

---

## Quick-Start Checklist

1. **Build** the product:
   ```bash
   mvn -pl phoebus-product -am install -DskipTests -Djavafx.platform=linux -T4
   ```
2. **Copy** your `settings.ini` to the product directory or pass it with
   `-settings /path/to/settings.ini`.
3. Add the report and OAuth2 keys shown above to your `settings.ini`.
4. Launch Phoebus and open the **Log Entry Table** — right-click →
   **Export HTML Report** to generate a report.
5. Open **Utility → Logbook Template Manager** to manage templates,
   logbooks, tags, and properties.
