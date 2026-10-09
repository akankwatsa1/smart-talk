import http.server
import socketserver
import os
import webbrowser

PORT = 5500
os.chdir(os.path.dirname(os.path.abspath(__file__)))

Handler = http.server.SimpleHTTPRequestHandler
Handler.extensions_map.update({
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.html': 'text/html'
})

print(f'SmartTalk local server starting on http://localhost:{PORT}')
with socketserver.TCPServer(('', PORT), Handler) as httpd:
    print('Serving at port', PORT)
