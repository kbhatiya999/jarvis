load('ext://uibutton', 'cmd_button', 'text_input', 'bool_input')

# The Jarvis workflow, as seen in Tilt (http://localhost:10350).
#
#   steps      one resource per job; each runs one hidden mise task
#   pipelines  press steps in order and stop at the first failure (release)
#
# There is one way to make an APK: the build step (scripts/build.sh, signed with the release
# key). release and install use it, and so do the GitHub workflows (Release, Build) for when
# the Mac isn't around, so every copy on any phone can update any other.
#
# Nothing runs by itself: not on start, save or commit. Press a button here, or run
# `mise run tilt:<name>` in a terminal: it presses the same button, streams the log and
# waits for the result. Resources allow parallel runs, otherwise a pipeline would block the
# steps it presses. Values you type (version, pairing code) go to .tilt-inputs/.


# Each run's output is also written to .tilt-inputs/logs/<name>.log, which tilt:* tasks stream.
def logged(name, cmd):
    return 'set -o pipefail; mkdir -p .tilt-inputs/logs; (%s) 2>&1 | tee .tilt-inputs/logs/%s.log' % (cmd, name)


def step(name, task, label, links=[]):
    local_resource(name, cmd=logged(name, 'mise run -q %s' % task), labels=[label], links=links,
                   trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False, allow_parallel=True)


def pipeline(name, steps):
    local_resource(name, cmd=logged(name, 'scripts/tilt-run.sh ' + ' '.join(steps)), labels=['pipelines'],
                   trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False, allow_parallel=True)


# ---- steps
step('setup', 'setup', 'setup')
step('env-check', 'env:check', 'setup')
step('keystore-gen', 'keystore:gen', 'setup')
step('secrets-push', 'secrets:push', 'setup')

step('lint', 'lint', 'build')
step('test', 'test', 'build')
step('build', 'build', 'build')
step('build-ci', 'build:ci', 'build')  # same build, on GitHub Actions

step('ios-build', 'ios:build', 'ios')
step('ios-release-build', 'ios:release-build', 'release')  # skips iOS if Xcode is missing
step('ios-sim', 'ios:sim', 'ios')

step('devices', 'devices', 'phone')
step('phone-pair', 'phone:pair', 'phone')
step('phone-connect', 'phone:connect', 'phone')
step('install', 'install', 'phone')  # dev build -> adb install

step('release-check', 'release:check', 'release')
step('release-publish', 'release:publish', 'release')
step('release-ci', 'release:ci', 'release')  # the release pipeline, on GitHub Actions

# The Jarvis bridge (bridge/): dev and tunnel are long-running services, started by their tilt:* task.
# Setup: the "Set up" button on bridge-check (below), or `mise run tilt:bridge-setup` to type the token hidden.
step('bridge-check', 'bridge:check', 'bridge', links=[
    link('https://dash.cloudflare.com/sign-up', '1. Cloudflare sign-up (free)'),
    link('https://dash.cloudflare.com/profile/api-tokens', '2. Make a token: "Edit Cloudflare Workers"'),
])
step('bridge-test', 'bridge:test', 'bridge')
step('bridge-deploy', 'bridge:deploy', 'bridge', links=[
    link('https://dash.cloudflare.com/?to=/:account/workers-and-pages', 'Cloudflare Workers dashboard'),
    link('https://gemini.google.com/customize/connectors', 'Gemini connectors (+ Custom)'),
])
step('bridge-deploy-ci', 'bridge:deploy-ci', 'bridge')  # the same deploy, on GitHub Actions
local_resource('bridge-dev', serve_cmd=logged('bridge-dev', 'mise run -q bridge:dev'), labels=['bridge'],
               links=[link('http://localhost:8787/health', 'Local bridge')],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)
local_resource('gollum', serve_cmd=logged('gollum', 'mise run -q gollum:serve'), labels=['notes'],
               links=[link('http://localhost:4567', 'Gollum')],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)
local_resource('otterwiki', serve_cmd=logged('otterwiki', 'mise run -q otterwiki:serve'), labels=['notes'],
               links=[link('http://localhost:5050', 'Otter Wiki')],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)
local_resource('gitit', serve_cmd=logged('gitit', 'mise run -q gitit:serve'), labels=['notes'],
               links=[link('http://localhost:5001', 'Gitit')],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)
local_resource('gitea', serve_cmd=logged('gitea', 'mise run -q gitea:serve'), labels=['forge'],
               links=[link('http://localhost:3000', 'Gitea')],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)
local_resource('forgejo', serve_cmd=logged('forgejo', 'mise run -q forgejo:serve'), labels=['forge'],
               links=[link('http://localhost:3001', 'Forgejo')],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)
local_resource('leafwiki', serve_cmd=logged('leafwiki', 'mise run -q leafwiki:serve'), labels=['notes'],
               links=[link('http://localhost:8080', 'LeafWiki')],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)
local_resource('bridge-tunnel', serve_cmd=logged('bridge-tunnel', 'mise run -q bridge:tunnel'), labels=['bridge'],
               trigger_mode=TRIGGER_MODE_MANUAL, auto_init=False)

# ---- pipelines
# release: everything happens on this Mac; GitHub only stores the release for Obtainium.
pipeline('release', ['release-check', 'lint', 'test', 'build', 'ios-release-build', 'release-publish'])

# ---- buttons with input boxes
cmd_button(
    name='bridge-setup', resource='bridge-check', icon_name='key', text='Set up Cloudflare',
    argv=['sh', '-c', 'scripts/bridge-configure.sh && tilt trigger bridge-check'],
    inputs=[
        text_input('BRIDGE_SETUP_TOKEN', label='Cloudflare API token (visible while typed; empty = keep saved)', placeholder='paste the token'),
        text_input('BRIDGE_SETUP_ACCOUNT', label='Account name (only if asked)'),
        text_input('BRIDGE_SETUP_SUBDOMAIN', label='workers.dev name (only if asked)', placeholder='e.g. jarvis-3fa9c1'),
        bool_input('BRIDGE_SETUP_GITHUB', label='Also copy to GitHub secrets (for bridge-deploy-ci)', true_string='true', false_string='false'),
    ],
)
cmd_button(
    name='release-with-version', resource='release', icon_name='rocket_launch', text='Release version',
    argv=['sh', '-c', 'scripts/tilt-input.sh version "$VERSION" && tilt trigger release'],
    inputs=[text_input('VERSION', label='Version (x.y.z)', placeholder='empty = next patch')],
)
cmd_button(
    name='release-ci-with-version', resource='release-ci', icon_name='cloud_upload', text='Release version on GitHub',
    argv=['sh', '-c', 'scripts/tilt-input.sh version "$VERSION" && tilt trigger release-ci'],
    inputs=[text_input('VERSION', label='Version (x.y.z)', placeholder='empty = next patch')],
)
cmd_button(
    name='pair-with-code', resource='phone-pair', icon_name='link', text='Pair',
    argv=['sh', '-c', 'scripts/tilt-input.sh pair-address "$ADDRESS" && scripts/tilt-input.sh pair-code "$CODE" && tilt trigger phone-pair'],
    inputs=[
        text_input('ADDRESS', label='IP:port (pairing dialog)', placeholder='192.168.1.23:37115'),
        text_input('CODE', label='Pairing code', placeholder='123456'),
    ],
)
