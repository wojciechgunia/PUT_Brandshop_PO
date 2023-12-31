package pl.put.brandshop.product.fasade;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.put.brandshop.product.entity.ProductFormDTO;
import pl.put.brandshop.product.entity.Response;
import pl.put.brandshop.product.mediator.ProductMediator;

@RestController
@RequestMapping(value = "/api/v1/product")
@RequiredArgsConstructor
public class ProductController
{
    private final ProductMediator productMediator;

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<?> get(HttpServletRequest request,
                                 @RequestParam(required = false) String name_like,
                                 @RequestParam(required = false) String data,
                                 @RequestParam(required = false) String _category,
                                 @RequestParam(required = false) Float price_min,
                                 @RequestParam(required = false) Float price_max,
                                 @RequestParam(required = false,defaultValue = "1") int _page,
                                 @RequestParam(required = false,defaultValue = "10") int _limit,
                                 @RequestParam(required = false,defaultValue = "price") String _sort,
                                 @RequestParam(required = false,defaultValue = "asc") String _order)
    {
        return productMediator.getProduct(_page,_limit,name_like,_category,price_min,price_max,data,_sort,_order,false);
    }

    @RequestMapping(method = RequestMethod.GET, value = "/admin-get")
    public ResponseEntity<?> getAdmin(HttpServletRequest request,
                                 @RequestParam(required = false) String name_like,
                                 @RequestParam(required = false) String data,
                                 @RequestParam(required = false) String _category,
                                 @RequestParam(required = false) Float price_min,
                                 @RequestParam(required = false) Float price_max,
                                 @RequestParam(required = false,defaultValue = "1") int _page,
                                 @RequestParam(required = false,defaultValue = "10") int _limit,
                                 @RequestParam(required = false,defaultValue = "price") String _sort,
                                 @RequestParam(required = false,defaultValue = "asc") String _order)
    {
        return productMediator.getProduct(_page,_limit,name_like,_category,price_min,price_max,data,_sort,_order,true);
    }

    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<Response> save(@RequestBody ProductFormDTO productFormDTO)
    {
        return productMediator.saveProduct(productFormDTO);
    }

    @RequestMapping(method = RequestMethod.PATCH)
    public ResponseEntity<Response> update(@RequestBody ProductFormDTO productFormDTO, @RequestParam String uid)
    {
        return productMediator.updateProduct(productFormDTO,uid);
    }

    @RequestMapping(method = RequestMethod.DELETE)
    public ResponseEntity<Response> delete(@RequestParam String uid)
    {
        return productMediator.deleteProduct(uid);
    }

    @RequestMapping(path = "/getexternal", method = RequestMethod.GET)
    public ResponseEntity<?> getProduct(@RequestParam String uid)
    {
        return productMediator.getProductExtend(uid);
    }

}
