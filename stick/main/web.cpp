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
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stick remote</title>
<style>
:root{color-scheme:dark;--bg:#000;--card:#1c1c1e;--line:#2c2c2e;--text:#fff;--dim:#8e8e93;--blue:#0a84ff;--red:#ff453a;--green:#30d158}
body{margin:0;background:var(--bg);color:var(--text);font:16px -apple-system,system-ui,sans-serif;
  padding:calc(env(safe-area-inset-top) + 16px) 16px 32px}
h1{font-size:28px;margin:8px 0 4px}p{color:var(--dim);margin:0 0 20px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:10px;margin-bottom:28px}
.b{position:relative;background:var(--card);border:0;border-radius:14px;color:var(--text);font:inherit;font-weight:600;
  padding:22px 8px;cursor:pointer}.b:active{background:var(--line)}
.b i{position:absolute;top:4px;right:8px;font-style:normal;color:var(--dim);font-weight:400;font-size:18px;display:none}
.edit .b i{display:block}.edit .b{outline:1px dashed var(--line)}
.card{background:var(--card);border-radius:14px;padding:16px}
h2{font-size:17px;margin:0 0 12px;display:flex;justify-content:space-between;align-items:center}
h2 button{background:none;border:0;color:var(--blue);font:inherit;cursor:pointer}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}
.chips button{background:var(--line);border:0;border-radius:999px;color:var(--text);font:inherit;font-size:14px;padding:6px 12px}
.row{display:flex;gap:8px}input{flex:1;min-width:0;background:var(--bg);border:1px solid var(--line);border-radius:10px;
  color:var(--text);font:inherit;padding:10px}
.go{background:var(--blue);border:0;border-radius:10px;color:#fff;font:inherit;font-weight:600;padding:10px 16px}
.go:disabled{opacity:.5}#msg{min-height:22px;margin-top:12px;color:var(--dim)}
.ok{color:var(--green)!important}.bad{color:var(--red)!important}
</style></head><body>
<h1>Stick remote</h1><p>Buttons the stick learned from your remotes.</p>
<div class="grid" id="grid"></div>
<div class="card"><h2>Teach a button <button id="ed">Edit</button></h2>
<div class="chips" id="chips"></div>
<div class="row"><input id="name" placeholder="Button name, e.g. Lamp brighter" maxlength="40"><button class="go" id="learn">Learn</button></div>
<div id="msg">Point the remote at the top of the stick, about 30 cm away.</div></div>
<script>
const $=id=>document.getElementById(id), msg=(t,c='')=>{$('msg').textContent=t;$('msg').className=c};
const SUGGEST=['Lamp on','Lamp off','Lamp brighter','Lamp dimmer','Lamp warm','Lamp cool','Diffuser'];
let names=[];
async function load(){names=(await (await fetch('/ir')).json()).buttons;draw()}
function draw(){
  $('grid').innerHTML='';
  for(const n of names){const b=document.createElement('button');b.className='b';b.textContent=n;
    const x=document.createElement('i');x.textContent='×';b.append(x);
    b.onclick=()=>document.body.classList.contains('edit')?del(n):send(n);$('grid').append(b)}
  if(!names.length)$('grid').innerHTML='<p>Nothing yet: teach one below.</p>';
  $('chips').innerHTML='';
  for(const s of SUGGEST.filter(s=>!names.some(n=>n.toLowerCase()==s.toLowerCase()))){
    const c=document.createElement('button');c.textContent=s;c.onclick=()=>{$('name').value=s;learn()};$('chips').append(c)}
}
async function send(n){msg('Sending '+n+'…');const r=await fetch('/ir/send?name='+encodeURIComponent(n),{method:'POST'});
  msg(r.ok?'Sent '+n:'Couldn’t send '+n,r.ok?'ok':'bad')}
async function del(n){if(!confirm('Forget '+n+'?'))return;await fetch('/ir/delete?name='+encodeURIComponent(n),{method:'POST'});load()}
async function learn(){
  const n=$('name').value.trim();if(!n)return $('name').focus();
  $('learn').disabled=true;msg('Now press “'+n+'” on the remote, pointed at the stick…');
  try{const r=await fetch('/ir/learn?name='+encodeURIComponent(n),{method:'POST'});
    if(r.ok){msg('Learned '+n+'. Tap it above to try it.','ok');$('name').value='';load()}
    else msg('Nothing came through. Closer (but 30 cm or more), pointed at the stick’s top, and try again.','bad')}
  catch(e){msg('The stick didn’t answer.','bad')}
  $('learn').disabled=false}
$('learn').onclick=learn;$('name').onkeydown=e=>{if(e.key=='Enter')learn()};
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
    };
    for (const auto& u : routes) httpd_register_uri_handler(server, &u);
    if (mdns_init() == ESP_OK) {   // http://stick.local
        mdns_hostname_set("stick");
        mdns_service_add(nullptr, "_http", "_tcp", 80, nullptr, 0);
    }
}

}  // namespace web
