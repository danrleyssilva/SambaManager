# Royal Server Access — documentação operacional

Este documento reúne as informações necessárias para desenvolver, publicar, operar e diagnosticar o Royal Server Access.

## 1. Visão geral

O Royal Server Access é um aplicativo JavaFX para computadores Windows que acessam o servidor Samba `192.168.0.93`.

O aplicativo:

- recebe o usuário e a senha Samba;
- testa automaticamente quais compartilhamentos o usuário pode acessar;
- apenas informa visualmente as pastas permitidas e negadas; o usuário não altera as seleções;
- mapeia as pastas autorizadas como unidades do Windows;
- atualiza ou remove somente os mapeamentos do servidor `192.168.0.93`;
- permite a troca da própria senha Samba;
- consulta, baixa, valida e abre atualizações do programa;
- registra localmente informações técnicas;
- envia ao servidor eventos de conexão e de alteração de senha, sem registrar senhas.

Os usuários são contas locais do servidor Arch Linux, cadastradas também no banco `tdbsam` do Samba.

## 2. Componentes e caminhos

### Computador de desenvolvimento

Raiz do projeto:

```text
C:\Users\Danrley\Documents\Codex\2026-08-26\referenced-chatgpt-conversation-this-is-an\SambaManager
```

Arquivos principais:

| Finalidade | Caminho no projeto |
|---|---|
| Aplicação JavaFX | `src/main/java/br/com/suaempresa/sambamanager/SambaManagerApp.java` |
| Mapeamento Samba | `src/main/java/br/com/suaempresa/sambamanager/service/WindowsDriveMappingService.java` |
| Troca de senha | `src/main/java/br/com/suaempresa/sambamanager/service/PasswordChangeService.java` |
| Auditoria central | `src/main/java/br/com/suaempresa/sambamanager/service/AuditService.java` |
| Atualizador | `src/main/java/br/com/suaempresa/sambamanager/service/UpdateService.java` |
| Configuração do cliente | `src/main/resources/samba.properties` |
| Certificado público incorporado | `src/main/resources/certs/royal-samba-ca.crt` |
| Ícone do instalador | `packaging/server-access.ico` |
| Empacotamento | `scripts/empacotar-exe.ps1` |
| Publicação completa | `scripts/publicar-atualizacao.ps1` |
| Backend atual | `server/samba_password_wsgi.py` |
| Serviço Gunicorn | `server/samba-password-gunicorn.service` |

### Servidor Arch Linux

| Finalidade | Caminho no servidor |
|---|---|
| Backend em execução | `/opt/samba-password-api/samba_password_wsgi.py` |
| Configuração | `/etc/samba-password-api/config.json` |
| Certificados TLS | `/etc/samba-password-api/tls/` |
| Instaladores e manifesto | `/opt/samba-password-api/releases/` |
| Log central de auditoria | `/var/log/royal-server-access-audit.log` |
| Rotação do log | `/etc/logrotate.d/royal-server-access-audit` |
| Configuração do Samba | `/etc/samba/smb.conf` |

Serviço ativo:

```text
samba-password-gunicorn.service
```

O serviço antigo `samba-password-api.service` deve permanecer desabilitado e inativo.

## 3. Configuração do servidor HTTPS

Conteúdo esperado de `/etc/samba-password-api/config.json`:

```json
{
  "bind_address": "192.168.0.93",
  "port": 8443,
  "certificate": "/etc/samba-password-api/tls/server.crt",
  "private_key": "/etc/samba-password-api/tls/server.key",
  "minimum_password_length": 8,
  "allowed_networks": ["192.168.0.0/16", "10.0.8.0/24"],
  "releases_directory": "/opt/samba-password-api/releases",
  "audit_log": "/var/log/royal-server-access-audit.log"
}
```

Sempre valide o JSON antes de reiniciar:

```bash
sudo python -m json.tool /etc/samba-password-api/config.json
sudo systemctl restart samba-password-gunicorn
sudo systemctl status samba-password-gunicorn --no-pager -l
```

Endpoints utilizados:

| Método | Endpoint | Função |
|---|---|---|
| `POST` | `/v1/change-password` | Alterar a senha Samba do próprio usuário |
| `POST` | `/v1/audit-login` | Registrar conexão validada pelo aplicativo |
| `GET` | `/v1/app-version` | Consultar a versão publicada |
| `GET` | `/v1/shares` | Ler os nomes atuais dos compartilhamentos configurados no Samba |
| `GET` | `/releases/<arquivo.exe>` | Baixar o instalador |

Somente endereços pertencentes a `allowed_networks` são aceitos.

## 4. Certificados

Arquivos no servidor:

```text
/etc/samba-password-api/tls/royal-samba-ca.key
/etc/samba-password-api/tls/royal-samba-ca.crt
/etc/samba-password-api/tls/server.key
/etc/samba-password-api/tls/server.crt
```

Regras importantes:

- nunca copiar ou distribuir arquivos `.key`;
- distribuir somente a CA pública `royal-samba-ca.crt`;
- manter a cópia pública incorporada em `src/main/resources/certs/royal-samba-ca.crt`;
- após substituir o certificado no servidor, incorporar a CA correta e gerar uma nova versão do aplicativo;
- o aplicativo confia especificamente na CA interna incorporada ao próprio programa;
- a conexão HTTPS atual não depende da instalação manual da CA no Windows.

Para conferir datas e conteúdo do certificado:

```bash
sudo openssl x509 -in /etc/samba-password-api/tls/server.crt -noout -subject -issuer -dates -ext subjectAltName
```

## 5. Executar durante o desenvolvimento

Pré-requisitos:

- JDK 21 ou superior com `jpackage`;
- Maven;
- JavaFX obtido pelo Maven;
- Windows PowerShell;
- WiX Toolset 3.11 para gerar o instalador EXE;
- Cliente OpenSSH do Windows para publicar.

Na raiz do projeto:

```powershell
mvn javafx:run
```

Quando executado dessa forma, o rodapé mostra `Versão desenvolvimento`.

## 6. Publicar uma atualização

Use sempre um número de versão maior que o publicado. Exemplo:

```powershell
Set-ExecutionPolicy -Scope Process Bypass

.\scripts\publicar-atualizacao.ps1 `
    -Version 4.4 `
    -ReleaseNotes "Adicionado registro central de conexoes e alteracoes de senha."
```

Para marcar uma atualização como obrigatória, acrescente:

```powershell
-Required
```

O script executa automaticamente:

1. compilação Maven;
2. geração do instalador com `jpackage`;
3. criação do `update.json`;
4. cálculo e validação do SHA-256;
5. envio por SCP;
6. validação no servidor;
7. instalação do EXE;
8. instalação do manifesto por último;
9. consulta ao endpoint de versão.

Podem ser solicitadas a senha SSH durante o SCP, a senha SSH durante a conexão e a senha do `sudo`.

Arquivos gerados localmente:

```text
dist-installer-<versão>/RoyalServerAccess-<versão>.exe
dist-installer-<versão>/update.json
```

Arquivos publicados:

```text
/opt/samba-password-api/releases/RoyalServerAccess-<versão>.exe
/opt/samba-password-api/releases/update.json
```

O manifesto é instalado por último para que nenhum computador receba a oferta de uma versão cujo instalador ainda não esteja disponível. Não é necessário reiniciar o Gunicorn ao publicar somente um novo EXE e um novo `update.json`.

Verificação manual:

```bash
curl -k https://192.168.0.93:8443/v1/app-version
sha256sum /opt/samba-password-api/releases/RoyalServerAccess-4.4.exe
```

É recomendável manter a versão atual e pelo menos uma versão anterior no servidor.

## 7. Atualizar o backend do servidor

Esta operação é necessária quando `server/samba_password_wsgi.py`, o serviço ou a configuração do backend forem alterados. Ela não faz parte da publicação comum do instalador.

No PowerShell:

```powershell
scp .\server\samba_password_wsgi.py `
    .\server\royal-server-access-audit.logrotate `
    rmadmin@192.168.0.93:/tmp/
```

No servidor:

```bash
sudo install -o root -g root -m 644 \
  /tmp/samba_password_wsgi.py \
  /opt/samba-password-api/samba_password_wsgi.py

sudo install -o root -g root -m 644 \
  /tmp/royal-server-access-audit.logrotate \
  /etc/logrotate.d/royal-server-access-audit

sudo touch /var/log/royal-server-access-audit.log
sudo chown root:root /var/log/royal-server-access-audit.log
sudo chmod 640 /var/log/royal-server-access-audit.log

sudo systemctl restart samba-password-gunicorn
sudo systemctl status samba-password-gunicorn --no-pager -l
```

## 8. Logs

### Log local do aplicativo

O aplicativo cria:

```text
<diretório de execução>\logs\samba-manager.log
```

O caminho absoluto aparece na primeira linha do próprio log:

```text
Aplicativo iniciado. Arquivo de log: ...
```

Esse arquivo contém verificações, mapeamentos, atualizações, respostas HTTP e erros técnicos. Senhas não são gravadas.

### Log central de auditoria

Arquivo:

```text
/var/log/royal-server-access-audit.log
```

Acompanhar em tempo real:

```bash
sudo tail -f /var/log/royal-server-access-audit.log
```

Consultar as últimas 100 linhas:

```bash
sudo tail -n 100 /var/log/royal-server-access-audit.log
```

Pesquisar um usuário:

```bash
sudo grep 'user=danrley.silva' /var/log/royal-server-access-audit.log
```

Eventos esperados:

```text
event=connection result=success user=danrley.silva source=192.168.2.70 computer=ROYALMAX-PC-1 app_version=4.4 accessible_shares=19
event=password-change result=success user=danrley.silva source=192.168.2.70
event=password-change result=denied user=danrley.silva source=192.168.2.70
```

O log é rotacionado semanalmente, comprimido e mantido por 12 semanas. Ele registra eventos do programa e troca de senha, mas não registra leitura, criação, alteração ou exclusão de cada arquivo. Para isso é necessário configurar auditoria própria do Samba, por exemplo `full_audit`.

### Log técnico do Gunicorn

```bash
sudo journalctl -u samba-password-gunicorn -n 100 --no-pager
sudo journalctl -u samba-password-gunicorn -f
```

O arquivo `/var/log/samba-password-api.log` pertence à implementação antiga sem Gunicorn e não é o log principal atual.

### Logs do Samba

Dependendo da configuração do Arch Linux:

```bash
sudo journalctl -u smb -n 100 --no-pager
sudo ls -la /var/log/samba/
```

## 9. Usuários, grupos e permissões Samba

Confirmar se um usuário existe no Samba:

```bash
sudo pdbedit -L | grep '^karyne.silva:'
```

Conferir grupos Linux:

```bash
groups karyne.silva
id karyne.silva
getent group grp_manutencao
```

Validar o `smb.conf`:

```bash
sudo testparm -s
```

Após alterar `/etc/samba/smb.conf`:

```bash
sudo testparm -s
sudo systemctl restart smb
```

Exemplo de compartilhamento:

```ini
[17 - Manutencao]
    path = "/srv/main_storage/17 - Manutenção"
    browseable = yes
    read only = no
    writable = yes
    valid users = @grp_manutencao
    force group = grp_manutencao
    create mask = 0660
    directory mask = 2770
    vfs objects = acl_xattr recycle
```

Além de `valid users`, todas as partes do caminho Linux precisam permitir travessia pelo usuário ou grupo. Verifique com:

```bash
sudo namei -l '/srv/main_storage/17 - Manutenção'
sudo getfacl '/srv/main_storage/17 - Manutenção'
```

O compartilhamento `Administracao`, apontado para a raiz do armazenamento, é mostrado e mapeado pelo aplicativo somente quando o usuário realmente consegue autenticá-lo. Quando `Administracao` está disponível, a tela administrativa substitui a lista comum.

## 10. Mapeamentos e credenciais do Windows

O Windows não permite facilmente múltiplas credenciais simultâneas para o mesmo servidor. Uma conexão antiga com `192.168.0.93` pode causar mensagem de senha incorreta mesmo com a senha correta.

Ao mapear pastas, o aplicativo procura letras livres de `F:` até `Z:`. Unidades já ocupadas por discos locais, outros servidores ou mapeamentos persistentes são ignoradas. Cada pasta e letra utilizada aparecem no log local. Se uma pasta não puder ser mapeada, as demais continuam sendo tentadas e a interface informa quando o resultado é parcial.

Esse intervalo comporta no máximo **21 unidades**. Se um usuário tiver acesso a mais de 21 compartilhamentos, os demais continuam acessíveis pelo caminho `\\192.168.0.93\<nome do compartilhamento>`, mas não recebem letra. Para disponibilizar todos no Explorador sem esse limite, será preciso uma estratégia separada, como atalhos para os compartilhamentos, em vez de uma letra por pasta.

Os novos mapeamentos são criados pela API de rede do Windows (`WNetAddConnection2`) com a opção `CONNECT_UPDATE_PROFILE`. Só são considerados concluídos quando aparecem em `HKCU:\Network`, são conexões ativas para o compartilhamento esperado e a letra pode ser aberta pela própria sessão do aplicativo. Isso faz o Windows lembrar as letras após sair do aplicativo ou reiniciar. As unidades são específicas do usuário Windows que executou o programa: não execute o Royal Server Access como outro usuário ou em uma sessão elevada se as pastas devem aparecer no Explorador normal do usuário. Uma unidade persistente pode aparecer inicialmente como **Desconectada** após o login até que a rede e o servidor estejam disponíveis; isso não significa necessariamente que o registro foi perdido.

O programa ainda grava a credencial comum no **Gerenciador de Credenciais do Windows**, mas alguns computadores não a reutilizam na reconexão após reiniciar. Por isso a versão nova inclui um iniciador separado e sem janela (`Royal Server Access Restore.exe`). Ao mapear pastas, ou ao entrar no programa com senha válida quando já há unidades lembradas para `192.168.0.93`, o programa registra esse iniciador em `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`. Ele inicia somente na sessão Windows do usuário atual, espera a rede estar disponível por até cerca de um minuto e reconecta explicitamente cada letra lembrada para esse servidor. Não escolhe letras novas nem substitui uma letra ligada a outra pasta. As entradas de `HKCU:\Network` não são apagadas nem renumeradas.

A senha da reconexão fica em uma **credencial genérica própria do programa** no Gerenciador de Credenciais do Windows, com destino `RoyalMax.RoyalServerAccess:192.168.0.93` e persistência no computador. Ao contrário da credencial comum de domínio, essa entrada é lida explicitamente pelo auxiliar; o Windows não precisa escolhê-la sozinho. A senha não é gravada em texto aberto em arquivo, manifesto, `samba.properties`, registro de inicialização ou log. O diagnóstico de cada inicialização fica em `%APPDATA%\RoyalServerAccess\auto-restore.log`. A credencial pertence ao perfil Windows que fez o mapeamento. Se a senha Samba mudar fora do aplicativo, entre novamente com a senha nova para atualizar a reconexão. Uma troca de senha bem-sucedida pelo aplicativo também atualiza a entrada própria. O botão **Limpar mapeamentos** remove o registro de inicialização, as duas credenciais desse servidor e qualquer arquivo protegido deixado pela versão de teste anterior, além das unidades deste servidor.

O log de reconexão registra o início do auxiliar e o resultado de cada letra. Erros de conflito ou de autenticação aparecem com códigos, sem registrar a senha.

Para ativar a reconexão em unidades **já existentes**, instale a nova versão, abra o programa na mesma conta Windows e use **Entrar** com a senha Samba atual. Não é necessário limpar ou mapear todas as unidades novamente. Verifique no log local a mensagem `Reconexão automática ativada` antes do teste com reinício.

Como qualquer senha armazenada no computador, essa conveniência exige que a conta Windows esteja protegida e que o equipamento não seja compartilhado sem controle. O Gerenciador de Credenciais protege a entrada em repouso, mas um processo executado com a própria conta Windows do usuário poderá lê-la. A reconexão depende da rede, do serviço SMB e da senha permanecer válida; não há garantia de sucesso em todas as condições externas.

Para verificar no computador, antes e depois de reiniciar:

```powershell
net use
Get-ChildItem HKCU:\Network
cmdkey /list:192.168.0.93
cmdkey /list:RoyalMax.RoyalServerAccess:192.168.0.93
reg query HKCU\Software\Microsoft\Windows\CurrentVersion\Run /v RoyalServerAccessRestore
Get-Content "$env:APPDATA\RoyalServerAccess\auto-restore.log" -Tail 20
```

Inspecionar conexões:

```powershell
net use
```

O botão **Limpar mapeamentos** fica disponível desde a abertura do aplicativo e não exige que o usuário informe credenciais. Ele remove somente conexões relacionadas ao servidor `192.168.0.93`. Durante a execução da limpeza, o botão é temporariamente desabilitado para impedir comandos duplicados. Quando algo é removido, o aplicativo informa que o computador precisa ser reiniciado e oferece a opção de reiniciar imediatamente.

A limpeza usa `WNetCancelConnection2` para remover também unidades persistentes **desconectadas** que ainda aparecem em `HKCU:\Network`, mesmo quando `net use` mostra lista vazia. Antes de tocar em cada letra, o programa verifica se o caminho salvo pertence ao servidor `192.168.0.93`. Se o Windows deixar um registro antigo após o cancelamento, somente a chave daquela letra é removida. O botão também apaga a credencial salva para esse servidor.

O botão **Atualizar pastas** está temporariamente desabilitado em todas as situações. Para reativá-lo em uma versão futura, altere `REFRESH_MAPPINGS_ENABLED` para `true` em `SambaManagerApp.java`, teste o fluxo e publique uma nova versão.

### Compartilhamentos criados depois da instalação

O backend lê as seções efetivas do Samba com `testparm -s` a cada consulta a `/v1/shares`; o nome não precisa entrar em uma nova compilação do aplicativo. Ao usar **Entrar**, o aplicativo baixa essa lista e testa cada compartilhamento com a conta Samba informada. A lista na tela passa a incluir os nomes novos, com marca de acesso somente quando o teste SMB é bem-sucedido. Se o catálogo HTTPS estiver indisponível, a lista local incorporada ao aplicativo é usada temporariamente e o status informa isso; nomes novos só aparecem quando o catálogo voltar.

Se o Windows já mantiver uma conexão com o mesmo servidor por outro usuário Samba, a verificação dessa segunda conta falha por conflito de credenciais da sessão Windows. O aplicativo informa essa causa e registra um resumo das falhas por categoria, sem gravar a senha nem repetir uma mensagem para cada pasta. A limpeza dos mapeamentos continua sendo uma ação explícita do usuário; nunca é feita automaticamente durante a verificação.

Na lista visual, um compartilhamento cujo nome começa com o nome exato de outro seguido de ` - ` fica recolhido sob ele. Por exemplo, `1 - Contabilidade - Controles` aparece sob `1 - Contabilidade` e pode ser expandido pela seta. Isso é somente organização da interface baseada nos nomes: cada compartilhamento ainda é verificado e mapeado separadamente, e o acesso ao filho não depende do acesso ao pai. Compartilhamentos com nomes sem esse padrão permanecem independentes. O catálogo ainda inclui `StorageRoot` e `BACKUPMAIN` durante os testes; a exclusão deles será feita depois.

Itens que agrupam subpastas exibem o mesmo checkbox da lista no estado intermediário, com um quadrado preto interno em vez do X ou de um visto. Esse estado sinaliza apenas "grupo expansível": a permissão real do compartilhamento pai fica armazenada separadamente e não é inferida do checkbox, portanto o mapeamento só usa permissões verificadas.

Ao expandir um grupo, as subpastas aparecem recuadas e ligadas por linhas contínuas, como em uma árvore. O nome repetido do compartilhamento pai é omitido apenas na apresentação; o nome SMB completo continua sendo usado na verificação e no mapeamento.

Se essa mesma conta Windows/Samba já tiver unidades lembradas de `192.168.0.93`, o aplicativo mapeia automaticamente os compartilhamentos **novos** que ela pode acessar, sem apagar ou renumerar as unidades existentes. A nova letra é salva para reconexão no próximo login do Windows. Quem ainda não tiver mapeamentos deve clicar em **Mapear pastas** após entrar. O comportamento administrativo continua especial: a conta com acesso a `Administracao` mapeia somente esse compartilhamento raiz.

Para ativar essa função, atualize primeiro `server/samba_password_wsgi.py` no servidor conforme a seção 7 e reinicie `samba-password-gunicorn`. Confirme:

```bash
curl -ksS https://192.168.0.93:8443/v1/shares
```

O retorno deve conter `{"shares": [...]}` com os nomes atuais. Depois gere e teste uma nova versão do aplicativo. Quando criar outro compartilhamento, valide `sudo testparm -s`, recarregue o Samba conforme o procedimento da organização e entre novamente no aplicativo com um usuário autorizado. Não é necessário reiniciar o Gunicorn a cada novo compartilhamento: o catálogo é lido a cada consulta. O limite de letras `F:` a `Z:` continua valendo; se acabarem, o programa informa o mapeamento parcial.

Após mudar grupos ou permissões, também pode ser necessário:

- limpar os mapeamentos;
- remover credenciais antigas do Windows;
- reiniciar o computador;
- entrar novamente no aplicativo;
- usar **Atualizar pastas**.

## 11. Troca de senha

A troca de senha utiliza HTTPS e o endpoint `/v1/change-password`.

Regras atuais:

- a nova senha deve ter pelo menos 8 caracteres;
- a senha atual é validada pelo Samba;
- o diálogo permanece aberto quando a nova senha é rejeitada pela validação local;
- senhas não aparecem nos logs;
- sucesso ou recusa são registrados no log central de auditoria.

O backend executa `smbpasswd -s` com a identidade Linux do próprio usuário. O Samba está configurado com `passdb backend = tdbsam`, `pam password change = No` e `unix password sync = No`. Portanto, a operação altera a senha Samba, não necessariamente a senha Linux.

## 12. Atualizador do aplicativo

O programa consulta automaticamente:

```text
https://192.168.0.93:8443/v1/app-version
```

Quando existe uma versão superior:

1. aparece um botão ao lado da versão instalada;
2. o usuário confirma a atualização;
3. o EXE é baixado;
4. o SHA-256 é comparado com o manifesto;
5. somente após a validação o instalador é aberto;
6. a janela do instalador é centralizada na tela sempre que o Windows permite.

Se o SHA-256 não corresponder, o instalador não será aberto.

## 13. Diagnóstico rápido

### Serviço não responde

No Windows:

```powershell
Test-NetConnection 192.168.0.93 -Port 8443
```

No servidor:

```bash
sudo systemctl status samba-password-gunicorn --no-pager -l
sudo ss -ltnp | grep 8443
sudo journalctl -u samba-password-gunicorn -n 100 --no-pager
```

### Certificado não confiável

- confirme que a CA incorporada no aplicativo corresponde à CA que assinou `server.crt`;
- nunca use nem distribua `royal-samba-ca.key`;
- confira as datas e o `subjectAltName` do certificado;
- gere outra versão do aplicativo depois de substituir a CA incorporada.

### Pasta funciona manualmente, mas não aparece no programa

Verifique:

1. se o nome em `samba.properties` é idêntico ao nome do compartilhamento no `smb.conf`;
2. grupos com `id <usuário>` e `getent group <grupo>`;
3. permissões e ACLs do caminho Linux;
4. resultado de `sudo testparm -s`;
5. conexões e credenciais antigas no Windows;
6. log local `logs\samba-manager.log`.

Nomes com acentos devem ser conferidos cuidadosamente. O nome exposto pelo compartilhamento e o caminho físico são coisas diferentes.

### Atualização não aparece

```bash
curl -k https://192.168.0.93:8443/v1/app-version
sudo cat /opt/samba-password-api/releases/update.json
sudo ls -lh /opt/samba-password-api/releases/
```

Confirme que a versão do manifesto é maior que a instalada e que o `downloadUrl` aponta exatamente para o nome do EXE publicado.

## 14. Segurança e manutenção

- nunca registrar senhas em logs;
- nunca colocar senhas em scripts ou no repositório;
- nunca distribuir chaves privadas `.key`;wh
- manter acesso ao SSH e ao `sudo` restrito ao administrador;
- fazer backup de `/etc/samba/smb.conf` e `/etc/samba-password-api/` antes de alterações importantes;
- validar JSON com `python -m json.tool`;
- validar Samba com `testparm -s`;
- publicar sempre com número de versão crescente;
- instalar sempre o manifesto `update.json` por último;
- manter pelo menos a versão anterior do instalador;
- revisar periodicamente o log central e o espaço em disco.

O endpoint de auditoria serve como registro operacional do aplicativo dentro das redes autorizadas. Ele não substitui uma trilha forense de acesso a arquivos do Samba.

## 15. Recurso especial `.ne`

A mensagem especial está armazenada em:

```text
src/main/resources/.ne
```

Ela é incorporada ao instalador. Para alterar a mensagem, edite o arquivo e publique uma nova versão. A sequência especial é reconhecida localmente, não é enviada ao servidor e não concede acesso às pastas.

Sequência configurada:

```text
0117721123826
```

Ao informar essa sequência no campo de senha e pressionar Enter ou **Entrar**, o aplicativo limpa o campo, pede confirmação e, após **OK**, mostra o conteúdo do arquivo `.ne`. O fluxo normal de autenticação não é executado.

## 16. Checklist de nova versão

- [ ] Alterações testadas com `mvn javafx:run`.
- [ ] Número de versão maior que o atual.
- [ ] Notas da versão revisadas.
- [ ] Certificado público correto incorporado.
- [ ] Publicação executada com `publicar-atualizacao.ps1`.
- [ ] SHA-256 local e remoto validados pelo script.
- [ ] `/v1/app-version` retorna a nova versão.
- [ ] Atualização testada em pelo menos um computador.
- [ ] Login, mapeamento e troca de senha testados.
- [ ] Log central de auditoria conferido.
