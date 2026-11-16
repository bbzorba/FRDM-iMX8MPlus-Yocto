SUMMARY = "Claude Code login for the board, from a token kept out of git"
DESCRIPTION = "The board has no browser for Claude Code's interactive login. Instead, Claude Code \
reads CLAUDE_CODE_OAUTH_TOKEN (a long-lived token from 'claude setup-token', run on a PC that has \
a browser; needs a Claude subscription) or ANTHROPIC_API_KEY (Anthropic Console, billed per use). \
The value comes from build-wayland/conf/site.conf (git-ignored) and lands in a root-only file."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# Set ONE of these in build-wayland/conf/site.conf (never in a tracked file):
#   BBZ_CLAUDE_OAUTH_TOKEN = "<output of: claude setup-token>"
#   BBZ_ANTHROPIC_API_KEY  = "<key from console.anthropic.com>"
BBZ_CLAUDE_OAUTH_TOKEN ??= ""
BBZ_ANTHROPIC_API_KEY ??= ""

# Rebuild when a credential changes, with only a SHA-256 of it in BitBake's signature files
BBZ_CLAUDE_OAUTH_TOKEN[vardepvalue] = "${@__import__('hashlib').sha256(d.getVar('BBZ_CLAUDE_OAUTH_TOKEN').encode()).hexdigest()}"
BBZ_ANTHROPIC_API_KEY[vardepvalue] = "${@__import__('hashlib').sha256(d.getVar('BBZ_ANTHROPIC_API_KEY').encode()).hexdigest()}"
do_install[vardeps] += "BBZ_CLAUDE_OAUTH_TOKEN BBZ_ANTHROPIC_API_KEY"

inherit allarch

do_configure[noexec] = "1"
do_compile[noexec] = "1"

python do_install() {
    import os, re

    creds = [("CLAUDE_CODE_OAUTH_TOKEN", d.getVar('BBZ_CLAUDE_OAUTH_TOKEN') or ""),
             ("ANTHROPIC_API_KEY", d.getVar('BBZ_ANTHROPIC_API_KEY') or "")]
    creds = [(k, v.strip()) for k, v in creds if v.strip()]
    if not creds:
        bb.note("No Claude credential in site.conf: Claude Code will ask for an interactive login")
        return
    for k, v in creds:
        # tokens/keys are single-line ASCII without quotes; refuse anything that could break the shell file
        if not re.fullmatch(r"[A-Za-z0-9._~+/=-]+", v):
            bb.fatal("%s contains unexpected characters (spaces, quotes or line breaks?)" % k)

    confdir = d.expand('${D}${sysconfdir}/claude-code')
    os.makedirs(confdir, exist_ok=True)
    os.chmod(confdir, 0o700)
    path = os.path.join(confdir, 'auth.env')
    with open(path, 'w') as f:
        f.write("# Claude Code credentials (from site.conf at build time). Root only.\n")
        for k, v in creds:
            f.write("export %s='%s'\n" % (k, v))
    os.chmod(path, 0o600)

    # Login shells (console, interactive SSH) load it; it is only readable by root anyway
    profdir = d.expand('${D}${sysconfdir}/profile.d')
    os.makedirs(profdir, exist_ok=True)
    with open(os.path.join(profdir, 'claude-auth.sh'), 'w') as f:
        f.write("[ -r /etc/claude-code/auth.env ] && . /etc/claude-code/auth.env\n")
}

FILES:${PN} = "${sysconfdir}/claude-code ${sysconfdir}/profile.d/claude-auth.sh"
ALLOW_EMPTY:${PN} = "1"
RDEPENDS:${PN} = "claude-code"
