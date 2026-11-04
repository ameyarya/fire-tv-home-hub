#include "web.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <esp_http_server.h>
#include <mdns.h>

#include "ir.h"

namespace web {

static Status status;

static const char PAGE[] = R"HTML(<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="theme-color" content="#0b0b0f">
<title>Stick remote</title>
<style>
:root{color-scheme:dark;--bg:#0b0b0f;--glass:#ffffff0d;--edge:#ffffff14;--text:#f5f5f7;--dim:#8e8e93;--blue:#0a84ff;
  --red:#ff453a;--green:#30d158;--amber:#ff9f0a;--accent:#64d2ff}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;min-height:100vh;background:radial-gradient(120% 60% at 50% -10%,#1d2a3a 0%,var(--bg) 60%);color:var(--text);
  font:16px/1.4 -apple-system,BlinkMacSystemFont,"SF Pro Text",system-ui,sans-serif;
  padding:calc(env(safe-area-inset-top) + 18px) 16px calc(env(safe-area-inset-bottom) + 96px)}
header{display:flex;align-items:center;justify-content:space-between;max-width:420px;margin:0 auto 6px}
h1{font-size:30px;font-weight:700;letter-spacing:-.5px;margin:0}
.pill{border:0;border-radius:999px;background:var(--glass);color:var(--blue);font:600 15px system-ui;padding:8px 14px}
.sub{max-width:420px;margin:0 auto 22px;color:var(--dim);font-size:14px}
.remote{max-width:420px;margin:0 auto;padding:22px 18px 24px;border-radius:28px;background:var(--glass);
  border:1px solid var(--edge);backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px)}
.top{display:flex;align-items:center;gap:10px;margin-bottom:20px}
.top h2{flex:1;margin:0;font-size:19px;font-weight:600}
.dot{width:8px;height:8px;border-radius:50%;background:#3a3a3c;transition:.15s}.dot.lit{background:var(--accent);box-shadow:0 0 12px var(--accent)}
.count{color:var(--dim);font-size:13px}
.keys{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.k{position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;aspect-ratio:1;
  border:0;border-radius:22px;background:#ffffff10;color:var(--text);font:500 13px system-ui;cursor:pointer;
  transition:transform .12s,background .2s}
.k:active{transform:scale(.94);background:#ffffff1c}
.k svg{width:28px;height:28px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.k.power{grid-column:1/-1;aspect-ratio:auto;height:88px;flex-direction:row;gap:12px;font-size:16px;
  background:linear-gradient(135deg,#ff453a,#d70015);box-shadow:0 10px 30px #ff453a40}
.k.power svg{width:30px;height:30px}
.k.new{background:transparent;outline:1.5px dashed #ffffff26;outline-offset:-1.5px;color:var(--dim)}
.k.power.new{background:transparent;box-shadow:none}
.k em{position:absolute;bottom:9px;font:600 10px system-ui;font-style:normal;letter-spacing:.3px;text-transform:uppercase;color:var(--amber)}
.k.power em{position:static}
.k.wait{outline:2px solid #ffd60a;color:#ffd60a;animation:pulse 1.1s infinite}
@keyframes pulse{50%{box-shadow:0 0 0 8px #ffd60a22}}
.k .x{position:absolute;top:-6px;right:-6px;display:none;width:24px;height:24px;border-radius:50%;background:var(--red);
  color:#fff;font:600 16px/24px system-ui}
.edit .k:not(.new) .x{display:block}.edit .k:not(.new){animation:wob .25s infinite alternate}
@keyframes wob{from{transform:rotate(-1.2deg)}to{transform:rotate(1.2deg)}}
.k.add{background:transparent;outline:1.5px dashed #ffffff1a;outline-offset:-1.5px;color:var(--dim)}
.tools{max-width:420px;margin:16px auto 0;display:flex;gap:10px}
.tools button{flex:1;border:0;border-radius:16px;background:var(--glass);border:1px solid var(--edge);color:var(--text);
  font:500 14px system-ui;padding:14px 10px}
.toast{position:fixed;left:50%;bottom:calc(env(safe-area-inset-bottom) + 20px);transform:translate(-50%,calc(100% + 60px));opacity:0;width:min(420px,calc(100% - 32px));
  padding:14px 16px;border-radius:18px;background:#1c1c1ef2;border:1px solid var(--edge);backdrop-filter:blur(20px);
  -webkit-backdrop-filter:blur(20px);font-size:14px;transition:transform .3s cubic-bezier(.2,.9,.3,1.2),opacity .2s;box-shadow:0 12px 40px #000a}
.toast.on{transform:translate(-50%,0);opacity:1}.toast.ok{border-color:#30d15866}.toast.bad{border-color:#ff453a66}
dialog{border:0;border-radius:24px;background:#1c1c1e;color:var(--text);padding:20px;width:min(360px,calc(100% - 32px))}
dialog::backdrop{background:#0008;backdrop-filter:blur(4px)}
dialog h3{margin:0 0 12px}dialog input{width:100%;background:#0b0b0f;border:1px solid var(--edge);border-radius:12px;color:var(--text);
  font:inherit;padding:12px}dialog menu{display:flex;gap:10px;justify-content:flex-end;margin:16px 0 0;padding:0}
dialog button{border:0;border-radius:12px;font:600 15px system-ui;padding:10px 16px;background:#2c2c2e;color:var(--text)}
dialog button.go{background:var(--blue)}
</style></head><body>
<header><h1>Diffuser</h1><button class="pill" id="ed">Edit</button></header>
<p class="sub">Tap a button to press it. One marked “not learned”: tap it, then press that button on the real remote.</p>
<section class="remote"><div class="top"><span class="dot" id="eye"></span><h2>Remote</h2><span class="count" id="count"></span></div>
<div class="keys" id="grid"></div></section>
<div class="tools"><button id="probe">Test receiver</button><button id="addBtn">Add a button</button></div>
<div class="toast" id="toast"></div>
<dialog id="dlg"><h3>New button</h3><input id="name" placeholder="Its name, e.g. Mist high" maxlength="40">
<menu><button value="cancel" id="cancel">Cancel</button><button class="go" id="ok">Learn</button></menu></dialog>
<script>
const $=id=>document.getElementById(id);
let tt;function msg(t,c=''){const e=$('toast');e.textContent=t;e.className='toast on '+c;clearTimeout(tt);
  if(c)tt=setTimeout(()=>e.classList.remove('on'),3500)}
const I={power:'<path d="M12 3v8"/><path d="M6.3 7.3a8 8 0 1 0 11.4 0"/>',
 mist:'<path d="M12 3s-5 5.6-5 9.2A5 5 0 0 0 12 17a5 5 0 0 0 5-4.8C17 8.6 12 3 12 3z"/><path d="M5 21h14"/>',
 light:'<path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.3 1 2.2h5.2c0-.9.4-1.7 1-2.2A6 6 0 0 0 12 3z"/>',
 colour:'<circle cx="12" cy="12" r="9"/><path d="M12 3v18"/><path d="M12 12 4.2 7.5"/><path d="m12 12 7.8-4.5"/>',
 brightness:'<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
 timer:'<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2.5"/><path d="M9 2h6"/>',
 other:'<circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="9"/>',add:'<path d="M12 5v14M5 12h14"/>'};
const svg=i=>'<svg viewBox="0 0 24 24">'+(I[i]||I.other)+'</svg>';
// The diffuser remote's buttons, each kept on the stick under its name; other learned buttons follow them.
const KEYS=[['Diffuser','Power','power'],['Diffuser mist','Mist','mist'],['Diffuser light','Light','light'],
  ['Diffuser colour','Colour','colour'],['Diffuser brightness','Brightness','brightness'],['Diffuser timer','Timer','timer']];
let names=[];
const has=n=>names.some(x=>x.toLowerCase()==n.toLowerCase()), editing=()=>document.body.classList.contains('edit');
async function load(){try{names=(await (await fetch('/ir')).json()).buttons}catch(e){msg('The stick didn’t answer.','bad')}draw()}
function key(name,label,icon,cls){const learned=has(name),k=document.createElement('button');
  k.className='k '+cls+(learned?'':' new');k.innerHTML=svg(icon)+'<span></span>'+(learned?'<b class="x">×</b>':'<em>Not learned</em>');
  k.querySelector('span').textContent=label;
  k.onclick=()=>learned?(editing()?del(name,label):send(name,label)):learnKey(name,label,k);$('grid').append(k)}
function draw(){$('grid').innerHTML='';
  KEYS.forEach(([n,l,i])=>key(n,l,i,i=='power'?'power':''));
  for(const n of names.filter(n=>!KEYS.some(([k])=>k.toLowerCase()==n.toLowerCase())))key(n,n,'other','');
  const a=document.createElement('button');a.className='k add';a.innerHTML=svg('add')+'<span>Add</span>';a.onclick=addKey;$('grid').append(a);
  $('count').textContent=KEYS.filter(([n])=>has(n)).length+' of '+KEYS.length+' learned'}
async function send(n,l){$('eye').classList.add('lit');setTimeout(()=>$('eye').classList.remove('lit'),250);
  const r=await fetch('/ir/send?name='+encodeURIComponent(n),{method:'POST'});if(!r.ok)msg('Couldn’t send '+l+'.','bad')}
async function del(n,l){if(!confirm('Forget '+l+'? It goes back to “not learned”.'))return;
  await fetch('/ir/delete?name='+encodeURIComponent(n),{method:'POST'});load()}
async function learnKey(n,l,k){if(document.querySelector('.k.wait'))return;
  k&&k.classList.add('wait');msg('Press '+l+' on the remote, pointed at the top of the stick (30 cm or more away)…');
  try{const r=await fetch('/ir/learn?name='+encodeURIComponent(n),{method:'POST'});
    if(r.ok){msg('Learned '+l+'. Tap it to try it.','ok');return load()}
    msg('Nothing came through for '+l+'. Aim at the stick’s top and tap it again.','bad')}
  catch(e){msg('The stick didn’t answer.','bad')}
  k&&k.classList.remove('wait')}
function addKey(){$('name').value='';$('dlg').showModal();$('name').focus()}
$('ok').onclick=e=>{e.preventDefault();const n=$('name').value.trim();if(!n)return;$('dlg').close();learnKey(n,n,null)};
$('cancel').onclick=e=>{e.preventDefault();$('dlg').close()};
$('name').onkeydown=e=>{if(e.key=='Enter')$('ok').click()};
$('addBtn').onclick=addKey;
$('probe').onclick=async()=>{$('probe').disabled=true;msg('For 5 seconds: hold any button on the remote, pointed at the stick…');
  try{const n=(await (await fetch('/ir/probe',{method:'POST'})).json()).changes;
    msg(n>20?'The stick sees this remote ('+n+' flickers).':'The stick saw nothing ('+n+'). Is the remote infrared?',n>20?'ok':'bad')}
  catch(e){msg('The stick didn’t answer.','bad')}
  $('probe').disabled=false};
$('ed').onclick=()=>{const on=document.body.classList.toggle('edit');$('ed').textContent=on?'Done':'Edit'};
load();
</script></body></html>)HTML";

/** A query parameter, URL-decoded; empty when missing. */
static std::string param(httpd_req_t* r, const char* key) {
    char q[200], v[120];
    if (httpd_req_get_url_query_str(r, q, sizeof q) != ESP_OK || httpd_query_key_value(q, key, v, sizeof v) != ESP_OK)
        return "";
    std::string out;
    for (char* p = v; *p; p++) {
        if (*p == '+') out += ' ';
        else if (*p == '%' && p[1] && p[2]) {
            char hex[3] = {p[1], p[2], 0};
            out += (char) strtol(hex, nullptr, 16);
            p += 2;
        } else out += *p;
    }
    return out;
}

static esp_err_t reply(httpd_req_t* r, const char* code, const char* json) {
    httpd_resp_set_status(r, code);
    httpd_resp_set_type(r, "application/json");
    httpd_resp_set_hdr(r, "Access-Control-Allow-Origin", "*");   // the TV's phone page calls it too
    return httpd_resp_sendstr(r, json);
}

static std::string quoted(const std::string& s) {
    std::string o = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        if ((unsigned char) c >= 0x20) o += c;
    }
    return o + "\"";
}

static esp_err_t page(httpd_req_t* r) {
    httpd_resp_set_type(r, "text/html");
    return httpd_resp_send(r, PAGE, sizeof PAGE - 1);
}

static esp_err_t list(httpd_req_t* r) {
    std::string j = "{\"buttons\":[";
    auto names = ir::names();
    for (size_t i = 0; i < names.size(); i++) j += (i ? "," : "") + quoted(names[i]);
    return reply(r, "200 OK", (j + "]}").c_str());
}

static esp_err_t learn(httpd_req_t* r) {
    std::string name = param(r, "name");
    if (name.empty()) return reply(r, "400 Bad Request", "{\"error\":\"name\"}");
    status(name.c_str(), "press it on the remote", 0xFFE0);
    std::vector<uint16_t> signal;
    if (!ir::learn(signal, 15000)) {
        status("Nothing came", "try again from the page", 0xFD20);
        return reply(r, "408 Request Timeout", "{\"error\":\"nothing received\"}");
    }
    if (!ir::save(name, signal)) {
        status("Couldn't keep it", "too many buttons?", 0xFD20);
        return reply(r, "507 Insufficient Storage", "{\"error\":\"not saved\"}");
    }
    status(name.c_str(), "learned", 0x07E0);
    char j[64];
    snprintf(j, sizeof j, "{\"ok\":true,\"pulses\":%d}", (int) signal.size());
    return reply(r, "200 OK", j);
}

static esp_err_t probe(httpd_req_t* r) {
    status("Testing", "press any remote button", 0xFFE0);
    int n = ir::probe(5000);
    status(n > 20 ? "Remote seen" : "Saw nothing", "", n > 20 ? 0x07E0 : 0xFD20);
    char j[48];
    snprintf(j, sizeof j, "{\"changes\":%d}", n);
    return reply(r, "200 OK", j);
}

static esp_err_t send(httpd_req_t* r) {
    std::string name = param(r, "name"), times = param(r, "times");
    std::vector<uint16_t> signal;
    if (!ir::load(name, signal)) return reply(r, "404 Not Found", "{\"error\":\"no such button\"}");
    bool ok = ir::send(signal, times.empty() ? 1 : atoi(times.c_str()));
    return reply(r, ok ? "200 OK" : "500 Internal Server Error", ok ? "{\"ok\":true}" : "{\"error\":\"send\"}");
}

static esp_err_t forget(httpd_req_t* r) {
    bool ok = ir::remove(param(r, "name"));
    return reply(r, ok ? "200 OK" : "404 Not Found", ok ? "{\"ok\":true}" : "{\"error\":\"no such button\"}");
}

void start(Status s) {
    status = s;
    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.stack_size = 8192;
    httpd_handle_t server;
    if (httpd_start(&server, &cfg) != ESP_OK) return;
    const httpd_uri_t routes[] = {
        {"/", HTTP_GET, page, nullptr},
        {"/ir", HTTP_GET, list, nullptr},
        {"/ir/learn", HTTP_POST, learn, nullptr},
        {"/ir/send", HTTP_POST, send, nullptr},
        {"/ir/delete", HTTP_POST, forget, nullptr},
        {"/ir/probe", HTTP_POST, probe, nullptr},
    };
    for (const auto& u : routes) httpd_register_uri_handler(server, &u);
    if (mdns_init() == ESP_OK) {   // http://stick.local
        mdns_hostname_set("stick");
        mdns_service_add(nullptr, "_http", "_tcp", 80, nullptr, 0);
    }
}

}  // namespace web
