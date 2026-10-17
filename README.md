# Livro de Oração Comum (IEAB, 1987) em HTML — leitura e edição

Site estático (HTML + CSS + JavaScript) para **ler** o livro no navegador e **editar** o texto,
gravando as alterações direto no GitHub. Um conversor em **Java** transforma o seu PDF em arquivos
JSON (um por seção). Cada edição vira um *commit* legível no histórico do repositório.

```
loc-1987-html/
├─ pom.xml                         build do conversor (Maven, Java 17+)
├─ secoes.txt                      divisão do livro em seções (editável)
├─ src/main/java/br/loc/Conversor.java
└─ docs/                           ← o site (GitHub Pages publica esta pasta)
   ├─ index.html · css/style.css · js/app.js
   └─ data/                        ← gerado pelo conversor
      ├─ indice.json
      └─ secoes/NN-titulo.json
```

## 1. Gerar os dados a partir do PDF

```bash
mvn -q package                                   # cria target/loc-conversor.jar
java -jar target/loc-conversor.jar  caminho/do/livro.pdf  docs/data  secoes.txt
```

O conversor identifica pelas fontes do PDF: **títulos** (negrito), **rubricas** (itálico, começam com `*`),
**falas** (`Oficiante -`, `Povo -`), **versos** dos Salmos (uma linha por verso) e **tabelas** dos lecionários
(colunas detectadas pela posição). Remove os rodapés e junta parágrafos que atravessam a página.
Os modos de cada seção (`prosa`, `versos`, `tabela`, `pular`) ficam em `secoes.txt`.

> A classificação é automática e feita por heurísticas; **revise** o resultado e corrija pelo próprio
> site (tipo do bloco, texto, tabelas). Se uma seção sair ruim, ajuste o modo em `secoes.txt` e rode de novo.

## 2. Ver localmente

```bash
cd docs && python3 -m http.server 8000      # abra http://localhost:8000
```
(Abrir o `index.html` direto do disco não funciona: o navegador bloqueia a leitura dos JSON.)

## 3. Publicar no GitHub

1. Crie o repositório e envie o projeto (inclua `docs/data/`).
2. *Settings → Pages → Deploy from a branch →* ramo `main`, pasta **`/docs`**.
3. Crie um **token de acesso fino**: *Settings → Developer settings → Personal access tokens →
   Fine-grained tokens*, limitado **a este repositório**, permissão **Contents: Read and write**.
4. No site, clique em **⚙**, preencha usuário, repositório, ramo (`main`) e pasta (`docs/data`), cole o
   token e use **Testar conexão**. O botão **✎ Editar** aparece.

## 4. Como editar

* **✎ Editar** liga o modo de edição: clique em qualquer trecho e digite. Passe o mouse sobre um bloco para
  mudar o tipo (título, rubrica, verso…), mover, inserir ou remover; tabelas ganham “＋ linha / − linha”.
* O que você digita é guardado como **rascunho no navegador** a cada pausa (e com Ctrl+S).
* **Publicar no GitHub** faz um commit do arquivo JSON da seção aberta. Se o arquivo mudou no GitHub depois
  de você abri-lo, a publicação é recusada para não sobrescrever o trabalho de outra pessoa
  (use **Baixar .json** para guardar o seu).
* O Pages leva cerca de um minuto para refletir o commit.
* Há também busca em todo o livro, tamanho de letra, tema claro/escuro e impressão limpa.

## Segurança e direitos autorais

* O token fica **só no seu navegador** (na sessão, ou no armazenamento local se marcar “lembrar”) e só é
  enviado para `api.github.com`. Não o compartilhe e use sempre token restrito a este repositório.
* Quem abre o site vê o botão ⚙, mas **sem token ninguém consegue gravar** — a proteção real é o token.
* **Quem pode ler**: se o repositório/site for público, qualquer pessoa lê o texto. A página de rosto do
  livro traz aviso de direitos reservados à IEAB (“nenhuma porção deste livro pode ser reproduzida […]
  sem a autorização da Igreja Episcopal do Brasil”). Antes de publicar o texto na internet, obtenha a
  autorização da IEAB ou mantenha o repositório privado (o GitHub Pages em repositório privado exige plano pago).
  Este projeto **não contém o texto do livro** — ele é gerado a partir do PDF que você fornece.
