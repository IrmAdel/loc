# Livro de Oração Comum (IEAB, 1987) em HTML — leitura e edição

Site estático (HTML + CSS + JavaScript) para **ler** o livro no navegador e **editar** o texto,
gravando as alterações direto no GitHub. Um conversor em **Java** transforma o seu PDF em arquivos
JSON (um por seção). Cada edição vira um *commit* legível no histórico do repositório.

```
loc-1987-html/
├─ .github/workflows/site.yml      converte o PDF e publica o site
├─ pom.xml                         build do conversor (Maven, Java 17+)
├─ secoes.txt                      divisão do livro em seções (editável)
├─ src/main/java/br/loc/Conversor.java
└─ docs/                           ← o site (GitHub Pages publica esta pasta)
   ├─ index.html · css/style.css · js/app.js
   └─ data/                        ← gerado pelo conversor
      ├─ indice.json
      └─ secoes/NN-titulo.json
```

## Passo a passo (só pelo site do GitHub, sem instalar nada)

1. Crie um repositório **privado** no GitHub (veja “Direitos autorais” abaixo).
2. Envie **todos os arquivos desta pasta** (arraste para *Add file → Upload files*, incluindo as pastas
   `.github`, `docs` e `src`) e **o PDF do livro renomeado para `livro.pdf`**, na raiz.
3. *Settings → Pages → Build and deployment → Source:* **GitHub Actions**.
4. Aba **Actions**: o fluxo “Converter PDF e publicar site” roda sozinho a cada envio. Na primeira vez ele
   converte o `livro.pdf` em `docs/data/` e publica o site (alguns minutos). O endereço aparece no fim da execução.
5. Crie o **token de acesso fino**: *Settings → Developer settings → Personal access tokens →
   Fine-grained tokens*, limitado **a este repositório**, permissão **Contents: Read and write**.
6. No site, clique em **⚙**, preencha usuário, repositório, ramo (`main`) e pasta (`docs/data`), cole o token e
   use **Testar conexão**. O botão **✎ Editar** aparece.

A conversão só acontece quando `docs/data/indice.json` ainda não existe. Suas edições nunca são
sobrescritas, a menos que você rode o fluxo manualmente (*Actions → Run workflow*) marcando “Reconverter”.
Cada edição publicada pelo site faz o fluxo republicar o site automaticamente.

O conversor identifica pelas fontes do PDF: **títulos** (negrito), **rubricas** (itálico, começam com `*`),
**falas** (`Oficiante -`, `Povo -`), **versos** dos Salmos e **tabelas** dos lecionários. Os modos de cada
seção (`prosa`, `versos`, `tabela`, `pular`) ficam em `secoes.txt`. A classificação é automática: **revise**
o resultado e corrija pelo próprio site.

Para rodar na sua máquina (opcional): `mvn -q package` e
`java -jar target/loc-conversor.jar livro.pdf docs/data secoes.txt`; depois `cd docs && python3 -m http.server`.

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
  autorização da IEAB ou mantenha o repositório privado (o GitHub Pages em repositório privado exige plano pago; o `livro.pdf` também fica visível em repositório público).
  Este projeto **não contém o texto do livro** — ele é gerado a partir do PDF que você fornece.
