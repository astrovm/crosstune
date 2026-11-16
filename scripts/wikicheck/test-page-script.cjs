// Execute the production DOM reader in a real browser. Run in the QA container, with no network.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const source = fs.readFileSync('app/src/main/java/com/astrovm/crosstune/ChordWiki.kt', 'utf8');
const script = process.argv[2] ? fs.readFileSync(process.argv[2], 'utf8') : source.match(/fun readPage\(\): String = """([\s\S]*?)"""\.trimIndent/)[1];
const browser = spawn(process.env.CHROME_BIN || '/chrome/chrome', ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--remote-debugging-pipe'], {stdio:['ignore','ignore','inherit','pipe','pipe']});
let next=0, buffer='';
const pending=new Map();
browser.stdio[4].on('data', data => {
  buffer += data;
  let end;
  while ((end=buffer.indexOf('\0')) >= 0) {
    const message=JSON.parse(buffer.slice(0,end));buffer=buffer.slice(end+1);
    const request=pending.get(message.id);
    if (request) { pending.delete(message.id); message.error ? request.reject(Error(JSON.stringify(message.error))) : request.resolve(message.result); }
  }
});
function send(method,params={},sessionId) {
  return new Promise((resolve,reject)=>{
    const id=++next;pending.set(id,{resolve,reject});
    browser.stdio[3].write(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})})+'\0');
  });
}
const cases=[
  ['automatic', '<div id="challenge-stage">Vérification en cours</div>', true, false],
  ['checkbox', '<form id="challenge-form"><input type="checkbox"></form>', true, true],
  ['shadow checkbox', '<div id="challenge-stage"><div id="widget"></div></div><script>document.getElementById("widget").attachShadow({mode:"open"}).innerHTML="<input type=checkbox>";</script>', true, true],
  ['same-origin frame', '<form id="challenge-form"><iframe srcdoc="<input type=checkbox>"></iframe></form>', true, true],
  ['opaque frame', '<form id="challenge-form"><iframe sandbox="allow-scripts" srcdoc="<input type=checkbox>"></iframe></form>', true, false],
  ['unrelated checkbox', '<input type="checkbox">', false, false],
  ['editor', '<textarea>[C]架空の歌</textarea>', false, false],
];
(async()=>{
  const {targetId}=await send('Target.createTarget',{url:'about:blank'});
  const {sessionId}=await send('Target.attachToTarget',{targetId,flatten:true});
  await send('Page.enable',{},sessionId);
  for (const [name,html,automatic,interactive] of cases) {
    await send('Page.navigate',{url:'about:blank'},sessionId);
    const {frameTree}=await send('Page.getFrameTree',{},sessionId);
    await send('Page.setDocumentContent',{frameId:frameTree.frame.id,html:'<!doctype html><html><head><script>window.fixtureReady=false;window.addEventListener("load",()=>window.fixtureReady=true);</script></head><body>'+html+'</body></html>'},sessionId);
    const expression=`(async()=>{while(!window.fixtureReady)await new Promise(r=>setTimeout(r,10));return (${script});})()`;
    const result=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true},sessionId);
    if(result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails));
    const page=JSON.parse(result.result.value);
    console.log(JSON.stringify({name,automatic:page.automatic,interactive:page.interactive}));
    assert.equal(page.automatic,automatic,name+' automatic');
    assert.equal(page.interactive,interactive,name+' interactive');
    if(name==='editor') assert.equal(page.hasEditor,true);
  }
  console.log('7 real-DOM cases passed');
})().then(()=>send('Browser.close')).catch(error=>{console.error(error);browser.kill();process.exitCode=1;});
setTimeout(()=>{browser.kill();process.exit(1);},30000).unref();
