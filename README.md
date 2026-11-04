# Assistente da Clínica

App Android que ajuda a recepção de uma clínica de terapias infantis a não esquecer
as rotinas do dia: agenda de sessões, confirmações com famílias e terapeutas,
remarcações de faltas e lançamento da agenda do mês seguinte.

Desenvolvido para **uso real**, com dados de saúde de crianças. Por isso, três
requisitos guiaram todas as decisões técnicas:

- **Segurança em primeiro lugar**: dados cifrados no aparelho, acesso por PIN individual
  e registro de quem fez o quê.
- **Sem servidor e sem nuvem**: tudo fica no celular da clínica. Não há conta, API
  ou banco de dados externo para vazar.
- **LGPD desde o início**: consentimento registrado, exportação e exclusão dos dados
  de uma criança, notificações que nunca mostram nomes.

> Nome, ícone e identidade da clínica foram substituídos por genéricos nesta versão pública.
> Todos os nomes e telefones nos testes são fictícios.

---

## ✨ Funcionalidades

- **"O que fazer"** como tela inicial: pendências atrasadas, de hoje e próximas, que
  não somem até serem resolvidas
- **Agenda** por dia, semana e mês; sessões avulsas (comum, anamnese, devolutiva) com
  aviso de conflito de horário
- **Horários fixos** por criança e lançamento da agenda do mês com prévia antes de salvar
- **Confirmações**: a sessão só fica confirmada quando a família *e* a terapeuta confirmam;
  mensagens prontas e editáveis, enviadas pelo WhatsApp com um toque (link `wa.me`, sem
  bibliotecas não oficiais)
- **Remarcar** com registro de quem pediu (família, terapeuta ou clínica)
- **Lembretes** por notificação, com alarmes exatos que voltam depois de reiniciar o celular
  e guia de economia de bateria por fabricante
- **Backup cifrado diário** e restauração em outro aparelho
- **Administração**: usuárias, registro de ações, resumos do mês (faltas e atendimentos
  por terapeuta), crianças inativas

## 🔐 Segurança

- Banco de dados cifrado com **SQLCipher**; a chave do banco fica embrulhada duas vezes:
  pelo PIN de cada usuária (**PBKDF2 + AES-GCM**) e por uma chave **AES-GCM do Android
  Keystore**, que só funciona com o celular desbloqueado. Copiar os arquivos para fora do
  aparelho não basta para abrir os dados
- **PIN individual** por pessoa, com espera crescente após erros e trava automática por
  inatividade ou ao sair do app
- Senha de backup para recuperar o acesso, com regras contra frases óbvias
- Arquivo de backup cifrado com **AES-GCM** (cabeçalho autenticado), chave e nonce próprios
  em cada cópia e código de conferência na restauração
- Telas protegidas contra prints e pré-visualização nos apps recentes; teclado sem
  aprendizado nos campos sensíveis
- Chave de assinatura e senhas fora do repositório (o release sem elas sai sem assinatura)

## 🛠️ Tecnologias

- **Kotlin** e **Jetpack Compose** (Material 3)
- **Room** + **SQLCipher** (migrações automáticas com esquemas versionados em `app/schemas`)
- **Android Keystore**, `AlarmManager`, notificações
- **JUnit** para as regras puras e testes instrumentados no aparelho
- Gradle (Kotlin DSL), R8

## 🧪 Testes

- **171 testes** das regras de negócio (rodam no computador, sem aparelho)
- **120 testes instrumentados** (banco cifrado, Keystore, sessão de acesso, migrações, backup)

```bash
cd android
./gradlew testDebugUnitTest          # regras de negócio
./gradlew connectedDebugAndroidTest  # no aparelho ou emulador
```

## 🧱 Organização do código

```
android/app/src/main/java/com/assistenteclinica/
├── dominio/      regras puras (agenda, pendências, lembretes, acesso, LGPD), sem Android
├── dados/        Room + SQLCipher, DAOs e migrações
├── seguranca/    Keystore, embrulho de chaves, arquivo de backup
├── acesso/       sessão de acesso (PIN, trava, registro de ações)
├── lembretes/    alarmes e notificações
├── backup/       preparar e compartilhar o backup do dia
└── ui/           telas em Compose
```

As regras de negócio ficam em `dominio/`, sem dependência do Android, para serem
testadas rápido e de forma isolada.

## 👤 Autor

Desenvolvido por [igorlira491-debug](https://github.com/igorlira491-debug).
