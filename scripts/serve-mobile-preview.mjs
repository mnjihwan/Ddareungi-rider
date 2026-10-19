import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {resolve,extname,sep} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../Ddareungi-rider/web/dist/',import.meta.url));
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon'};
http.createServer(async(req,res)=>{
 const url=new URL(req.url,'http://localhost');
 if(url.pathname.startsWith('/api/v1/')){
  const headers={...req.headers,host:'127.0.0.1:8080'};
  delete headers.cookie;delete headers.authorization;
  const proxy=http.request({hostname:'127.0.0.1',port:8080,path:url.pathname+url.search,method:req.method,headers},upstream=>{
   res.writeHead(upstream.statusCode,upstream.headers);upstream.pipe(res);
  });
  proxy.setTimeout(95000,()=>proxy.destroy());
  proxy.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();});
  req.pipe(proxy);return;
 }
 if(!['GET','HEAD'].includes(req.method)){res.writeHead(405);res.end();return;}
 try{
  const pathname=decodeURIComponent(url.pathname);
  const file=resolve(root,'.'+(pathname==='/'?'/index.html':pathname));
  if(!file.startsWith(resolve(root)+sep)||pathname.split('/').some(x=>x.startsWith('.'))){res.writeHead(404);res.end();return;}
  const body=await readFile(file);
  res.writeHead(200,{'Content-Type':mime[extname(file)]||'application/octet-stream','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  res.end(req.method==='HEAD'?undefined:body);
 }catch{res.writeHead(404);res.end();}
}).listen(5174,'127.0.0.1',()=>console.log('HTTPS preview origin on 127.0.0.1:5174'));
