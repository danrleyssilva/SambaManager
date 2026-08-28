# Samba Manager

Aplicativo Windows para mostrar e mapear compartilhamentos de um servidor Samba.

## Pré-requisitos

- JDK 21 LTS
- Maven 3.9 ou superior
- Windows PowerShell (já presente no Windows)

No VS Code, instale as extensões **Extension Pack for Java** e **JavaFX Support**.

## Executar

```powershell
cd SambaManager
mvn javafx:run
```

Os compartilhamentos e o IP do servidor ficam em `src/main/resources/samba.properties`.

Antes de gerar uma versão para distribuição, copie a CA pública do servidor para
`src/main/resources/certs/royal-samba-ca.crt`. O aplicativo a instalará automaticamente
no repositório de certificados do usuário Windows quando for necessária para alterar senha.

## Segurança

O aplicativo não grava a senha. A alteração/reset de senha ficará em uma segunda etapa, por meio de um serviço protegido no servidor Arch Linux; não deve ser feita diretamente pelo aplicativo dos usuários.
